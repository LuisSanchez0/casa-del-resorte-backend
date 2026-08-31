package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NormalSaleDetailHandler implements SaleDetailHandler {

    private final InventoryRepository inventoryRepository;
    private final InventoryLotService lotService;
    private final SaleDetailRepository detailRepository;
    
    @Override
    public FamilyCategory getType() {
        return FamilyCategory.NORMAL;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SaleDetail addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO) {
        // Cerrojo de fila ANTES de leer el stock: descontar es un
        // leer-decidir-guardar, y sin el dos ventas simultaneas del mismo
        // producto se pisan el descuento. Va primero que el de los lotes para
        // que todas las transacciones pidan los cerrojos en el mismo orden.
        Inventory inventory = inventoryRepository
                .findByProductIdAndWorkshopIdForUpdate(
                        requestDTO.product(),
                        SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe un inventario para el producto seleccionado"));
        if (requestDTO.quantity() == null) {
            throw new ValidationException("La cantidad a remover es obligatoria");
        }
        inventory.removeStock(requestDTO.quantity());
        SaleDetail detail = createDetail(inventory, requestDTO);
        sale.addDetail(detail);
        // El detalle tiene que estar persistido ANTES de asignarle lotes: la
        // asignacion apunta a el por clave ajena, y con un detalle todavia
        // transitorio el INSERT viajaria con sale_detail_id nulo.
        detailRepository.saveAndFlush(detail);
        // Y aqui, en el MISMO punto en que el stock sale del inventario, se
        // captura el costo historico. No al emitir el documento: entre agregar
        // la linea y facturarla puede entrar mercaderia mas cara, y costear
        // entonces le atribuiria a esta venta un costo que no es el suyo.
        lotService.consumeForSaleDetail(
                detail, inventory, requestDTO.quantity());
        return detail;
    }

    /**
     * Devuelve las existencias Y las unidades a los lotes EXACTOS de los que
     * salieron. Las dos cosas van juntas: reponer el stock sin reponer el lote
     * dejaria un inventario con mas existencias que respaldo, y la siguiente
     * venta se quedaria sin lotes que consumir.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(SaleDetail saleDetail) {
        // Devolver tambien escribe el stock, asi que necesita el mismo cerrojo.
        Inventory inventory = inventoryRepository
                .findByProductIdAndWorkshopIdForUpdate(
                        saleDetail.getProduct().getId(),
                        SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe un inventario para el producto seleccionado"));
        inventory.addStock(saleDetail.getQuantity());
        lotService.restoreSaleDetail(saleDetail);
    }

    private SaleDetail createDetail(Inventory inventory, AddSaleDetailRequestDTO requestDTO) {
        PricingOutcome pricing = resolvePricing(
                requestDTO.price(), inventory.getPrice(), inventory.getCost());
        BigDecimal price = pricing.price();

        // El inventario ya esta BLOQUEADO y es una entidad gestionada: asignarle
        // el precio aqui lo deja en la misma transaccion que la linea, de modo
        // que no puede quedar una venta con precio nuevo y un catalogo sin el.
        if (pricing.becomesCatalogPrice()) {
            inventory.setPrice(pricing.price());
        }

        BigDecimal subTotal = price.multiply(
                BigDecimal.valueOf(requestDTO.quantity()));
        return SaleDetail.builder()
                .quantity(requestDTO.quantity())
                .price(price)
                .product(inventory.getProduct())
                .subTotal(subTotal)
                .invoiceDescription(requestDTO.invoiceDescription())
                .build();
    }

}
