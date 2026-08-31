package com.cns.spring_house.acquisition.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.entity.SaleDetailCostAllocation;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.acquisition.repository.SaleDetailCostAllocationRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.entity.Supplier;

import lombok.RequiredArgsConstructor;

/**
 * El libro de lotes: la unica puerta por la que se crean, se consumen y se
 * devuelven existencias con costo.
 *
 * Toda la aritmetica del FIFO vive AQUI y en ningun otro sitio. No es orden por
 * orden: es lo que hace que consumir al vender y devolver al anular sean
 * simetricos por construccion. Repartidos entre los handlers, cada categoria
 * habria acabado con su propia version de "cual es el lote siguiente" y solo una
 * seria la correcta.
 *
 * <h2>El invariante</h2>
 *
 * <pre>
 * inventory.stock = SUM(quantity_remaining de sus lotes)
 * </pre>
 *
 * Se mantiene porque TODO movimiento de stock pasa por aqui, no solo las
 * compras: una transformacion que produce una pieza, una maleta que se arma y
 * una que se desarma tambien dejan su lote (de origen {@code PRODUCTION}). Sin
 * esa simetria habria stock sin lote, y el FIFO se toparia con lineas de venta
 * que solo podria costear a medias.
 *
 * <h2>Concurrencia</h2>
 *
 * Ni un solo camino de escritura lee un remanente sin bloquearlo antes. Las dos
 * consultas de lote llevan {@code PESSIMISTIC_WRITE}, es decir
 * {@code SELECT ... FOR UPDATE}: leer, decidir en Java y guardar despues es
 * exactamente el patron que permitia que dos ventas simultaneas sirvieran
 * catorce unidades de un lote de diez.
 *
 * <h2>Cobertura de costo</h2>
 *
 * Una linea de venta queda costeada solo si TODAS las unidades que consumio
 * salieron de lotes comprados. Si alguna vino de produccion, {@code costTotal}
 * se queda en NULL —no en cero—: cero significaria que la mercaderia fue gratis
 * y convertiria el precio entero en ganancia. NULL significa lo que de verdad
 * pasa: esa linea no tiene costo de adquisicion conocido, y el panel lo dice.
 * Es tambien lo que impide la linea "a medio costear" que el modelo prohibe.
 */
@Service
@RequiredArgsConstructor
public class InventoryLotService {

    private final InventoryAcquisitionRepository acquisitionRepository;
    private final SaleDetailCostAllocationRepository allocationRepository;

    /**
     * Misma fuente de "ahora" que los sellos financieros de la venta y que el
     * cierre de caja. `acquiredAt` es una fecha de NEGOCIO —es la que agrupa las
     * graficas de compras—, asi que no puede salir de un {@code now()} desnudo
     * que resuelva contra la zona por defecto de la JVM, ni mucho menos del
     * reloj del cliente.
     */
    private final Clock clock;

    // ── Entradas ─────────────────────────────────────────────────

    /**
     * Registra una COMPRA real y devuelve el lote creado.
     *
     * No toca {@code inventory.stock}: de eso se encarga el handler de la
     * categoria, que es quien sabe si ademas hay que crear unidades fisicas. Las
     * dos cosas ocurren en la misma transaccion, asi que o quedan las dos o no
     * queda ninguna.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryAcquisition registerPurchase(
            Inventory inventory,
            Supplier supplier,
            AcquisitionType acquisitionType,
            int units,
            BigDecimal unitCost,
            Employee employee) {

        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades a agregar deben ser mayores que cero");
        }
        if (supplier == null) {
            throw new ValidationException("El proveedor es obligatorio");
        }
        if (acquisitionType == null) {
            throw new ValidationException(
                    "El tipo de adquisicion es obligatorio");
        }
        if (unitCost == null) {
            throw new ValidationException("El costo unitario es obligatorio");
        }
        if (unitCost.signum() < 0) {
            throw new ValidationException(
                    "El costo unitario no puede ser negativo");
        }
        // El proveedor tiene que ser del MISMO taller que el inventario. Sin
        // esta comprobacion, conocer el UUID de un proveedor ajeno bastaria para
        // atribuirle una compra de otro taller.
        requireSameWorkshop(inventory, supplier);

        return acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(inventory.getWorkshop())
                .inventory(inventory)
                .supplier(supplier)
                .employee(employee)
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(acquisitionType)
                .quantityReceived(units)
                .quantityRemaining(units)
                .unitCost(unitCost)
                .acquiredAt(LocalDateTime.now(clock))
                .build());
    }

    /**
     * Registra una entrada que NO se compro: salida de una transformacion,
     * maleta recien armada o componente devuelto al desarmarla.
     *
     * Sin proveedor, sin tipo y sin costo, que es justo lo que el CHECK
     * {@code ck_inventory_acquisition_origin_shape} exige de un lote
     * {@code PRODUCTION}. Existe para que el invariante entre existencias y
     * remanentes no se rompa por las entradas que no son compras, NO para
     * atribuirles un costo que nadie conoce.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InventoryAcquisition registerProduction(Inventory inventory, int units) {
        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades producidas deben ser mayores que cero");
        }

        return acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(inventory.getWorkshop())
                .inventory(inventory)
                .origin(AcquisitionOrigin.PRODUCTION)
                .quantityReceived(units)
                .quantityRemaining(units)
                .acquiredAt(LocalDateTime.now(clock))
                .build());
    }

    // ── Salidas ──────────────────────────────────────────────────

    /**
     * Consume {@code units} del inventario en orden FIFO y deja constancia de
     * que lote alimento la linea de venta, fijando su {@code costTotal}.
     *
     * Es el UNICO momento en que se captura el costo historico de una venta, y
     * es el mismo en el que el stock sale del inventario (al agregar la linea,
     * no al emitir el documento): capturarlo mas tarde permitiria que una compra
     * posterior cambiara el costo de una venta ya hecha.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeForSaleDetail(
            SaleDetail saleDetail, Inventory inventory, int units) {

        List<Consumption> consumptions = consume(inventory, units);

        // Basta UNA unidad sin costo para que la linea entera deje de estar
        // cubierta. Costear solo la parte comprada daria un costo mas bajo que
        // el real y, por tanto, una ganancia inventada.
        boolean fullyCovered = consumptions.stream()
                .allMatch(consumption -> consumption.acquisition().hasCost());

        BigDecimal costTotal = BigDecimal.ZERO;

        for (Consumption consumption : consumptions) {
            InventoryAcquisition acquisition = consumption.acquisition();
            BigDecimal unitCost = acquisition.hasCost()
                    ? acquisition.getUnitCost()
                    : BigDecimal.ZERO;
            BigDecimal subtotal = unitCost.multiply(
                    BigDecimal.valueOf(consumption.quantity()));

            // La asignacion se guarda SIEMPRE, tenga costo o no: es la traza que
            // permite devolver el stock al lote exacto cuando la venta se quita,
            // se anula o se revierte. Sin ella habria que adivinar.
            allocationRepository.save(SaleDetailCostAllocation.builder()
                    .saleDetail(saleDetail)
                    .acquisition(acquisition)
                    .quantity(consumption.quantity())
                    .unitCostSnapshot(unitCost)
                    .subtotalCost(subtotal)
                    .build());

            costTotal = costTotal.add(subtotal);
        }

        saleDetail.setCostTotal(fullyCovered ? costTotal : null);
    }

    /**
     * Consume {@code units} en orden FIFO SIN dejar traza. Lo usan los
     * movimientos que sacan stock pero no son una venta: armar una maleta con
     * componentes propios y consumir la pieza de entrada de una transformacion.
     *
     * No llevan asignacion porque no hay ninguna linea de venta a la que
     * atribuir un costo, y porque su vuelta —desarmar la maleta— repone las
     * unidades como produccion, no como el lote original.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeWithoutTrace(Inventory inventory, int units) {
        consume(inventory, units);
    }

    /**
     * Recorre los lotes disponibles del mas antiguo al mas nuevo, tomando de
     * cada uno lo que pueda hasta completar lo pedido.
     *
     * Si al terminar falta algo, la operacion ENTERA se rechaza. No se costea a
     * medias ni se sirve lo que hay: que las existencias digan cinco y los lotes
     * sumen tres es una inconsistencia del modelo, no un caso de negocio, y
     * seguir adelante la propagaria a la contabilidad. La excepcion revierte la
     * transaccion, de modo que ningun lote queda medio consumido.
     */
    private List<Consumption> consume(Inventory inventory, int units) {
        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades a consumir deben ser mayores que cero");
        }

        List<InventoryAcquisition> lots =
                acquisitionRepository.findAvailableForUpdate(inventory.getId());

        List<Consumption> consumptions = new ArrayList<>();
        int pending = units;

        for (InventoryAcquisition lot : lots) {
            if (pending == 0) {
                break;
            }
            int taken = lot.consume(Math.min(pending, lot.getQuantityRemaining()));
            pending -= taken;
            consumptions.add(new Consumption(lot, taken));
        }

        if (pending > 0) {
            throw new ValidationException(
                    "El inventario no tiene lotes suficientes para cubrir la salida: "
                    + "faltan " + pending + " unidades sin respaldo de adquisicion");
        }

        acquisitionRepository.saveAll(lots);

        return consumptions;
    }

    // ── Devoluciones ─────────────────────────────────────────────

    /**
     * Deshace el consumo de una linea: devuelve a CADA lote exactamente lo que
     * le tomo y borra la traza.
     *
     * Devolver al lote exacto y no al mas nuevo o al mas viejo es la razon de
     * ser de la tabla de asignaciones. Reponer en el lote equivocado no rompe
     * ningun total —las existencias cuadrarian igual— pero corrompe el costo de
     * la SIGUIENTE venta que consuma de ahi, y ese error ya no se puede
     * detectar mirando los numeros.
     *
     * Los lotes se releen BLOQUEADOS y por su id, no con la consulta del FIFO:
     * un lote agotado tiene remanente cero y aquella lo filtra, pero es
     * precisamente al que hay que devolver.
     *
     * `costTotal` vuelve a null porque la linea deja de tener consumo asociado.
     * En un borrador que se sigue editando esto es lo correcto; en una venta
     * revertida la linea es historia de algo que se deshizo, y su costo ya no
     * describe mercaderia entregada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreSaleDetail(SaleDetail saleDetail) {
        List<SaleDetailCostAllocation> allocations =
                allocationRepository.findBySaleDetailId(saleDetail.getId());

        if (allocations.isEmpty()) {
            return;
        }

        List<UUID> lotIds = allocations.stream()
                .map(allocation -> allocation.getAcquisition().getId())
                .distinct()
                .toList();

        List<InventoryAcquisition> lots =
                acquisitionRepository.findAllByIdForUpdate(lotIds);

        for (SaleDetailCostAllocation allocation : allocations) {
            UUID lotId = allocation.getAcquisition().getId();
            InventoryAcquisition lot = lots.stream()
                    .filter(candidate -> candidate.getId().equals(lotId))
                    .findFirst()
                    .orElseThrow(() -> new ValidationException(
                            "No existe el lote al que devolver las unidades"));
            lot.restore(allocation.getQuantity());
        }

        acquisitionRepository.saveAll(lots);
        allocationRepository.deleteAll(allocations);

        saleDetail.setCostTotal(null);
    }

    // ── Apoyo ────────────────────────────────────────────────────

    private void requireSameWorkshop(Inventory inventory, Supplier supplier) {
        UUID inventoryWorkshop = inventory.getWorkshop() == null
                ? null
                : inventory.getWorkshop().getId();
        UUID supplierWorkshop = supplier.getWorkshop() == null
                ? null
                : supplier.getWorkshop().getId();

        if (inventoryWorkshop == null
                || !inventoryWorkshop.equals(supplierWorkshop)) {
            throw new ValidationException(
                    "El proveedor no pertenece al taller del inventario");
        }
    }

    /** Cuanto se tomo de un lote concreto durante un consumo. */
    private record Consumption(InventoryAcquisition acquisition, int quantity) { }

}
