package com.cns.spring_house.sale.handler;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ContainerSaleDetailHandler implements SaleDetailHandler {
    
    private final InventoryItemRepository repository;
    private final InventoryRepository inventoryRepository;
    private final InventoryLotService lotService;
    private final SaleDetailRepository detailRepository;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.CONTAINER;
    }

    /**
     * Solo el contenedor mueve stock. Sus componentes ya salieron del inventario
     * individual cuando se armo la maleta (ContainerInventoryHandler descuenta ahi
     * el stock de cada hoja y las deja UNAVAILABLE), asi que aqui unicamente
     * cambian de estado: siguen dentro del contenedor, pero ese contenedor ya esta
     * vendido.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public SaleDetail addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO) {
        if (requestDTO.item() == null) {
            throw new ValidationException(
                    "El item es obligatorio");
        }
        // Acotado al taller del token: un contenedor de otro taller no debe ser
        // vendible ni siquiera conociendo su UUID.
        InventoryItem item = repository
                .findByIdAndInventoryWorkshopId(
                        requestDTO.item(), SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el item especificado"));
        if (item.getStatus() != InventoryItemStatus.AVAILABLE) {
            throw new ValidationException(
                    "El item especificado no se encuentra disponible para su venta");
        }
        // Cerrojo de fila del inventario ANTES de tocar sus existencias, y en
        // una sola consulta por el item para no inicializar antes el proxy
        // perezoso —que seria justo la lectura sin bloquear que se evita—.
        Inventory inventory = lockedInventoryOf(item);
        item.setStatus(InventoryItemStatus.SOLD);
        inventory.removeStock(1);
        item.getContainedItems().forEach((child) -> {
            child.setStatus(InventoryItemStatus.SOLD);
        });
        SaleDetail detail = createDetail(item, requestDTO);
        sale.addDetail(detail);
        // El detalle debe existir en la base antes de que una asignacion pueda
        // apuntarlo por clave ajena.
        detailRepository.saveAndFlush(detail);
        // Una unidad fisica sale del inventario, asi que una unidad de lote se
        // consume. Cual pieza concreta se despacha lo decide quien vende; el
        // COSTO se consume en orden FIFO, que es la convencion contable y no
        // exige que la pieza vendida sea literalmente la mas antigua.
        lotService.consumeForSaleDetail(detail, inventory, 1);
        return detail;
    }

    /**
     * Inverso exacto de {@link #addDetail}: el contenedor vuelve a estar
     * disponible y devuelve su unidad al stock, y sus componentes regresan a
     * UNAVAILABLE porque siguen fisicamente dentro de el.
     *
     * El stock individual de los componentes NO se toca. Sumarlo aqui inventaba
     * unidades: esas hojas ya se habian descontado al armar la maleta y nunca se
     * volvieron a descontar al venderla, de modo que quitar el detalle las
     * ofrecia sueltas en el inventario mientras seguian dentro del contenedor.
     * Devolverlas al inventario individual es trabajo del desarmado
     * (ContainerTransformationHandler), que si rompe la relacion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void restore(SaleDetail saleDetail) {
        InventoryItem item = saleDetail.getItem();
        if (item == null) {
            throw new ValidationException(
                    "El detalle especificado necesita un item");
        }
        if (item.getStatus() != InventoryItemStatus.SOLD) {
            throw new ValidationException(
                    "El item especificado no se ha vendido");
        }
        Inventory inventory = lockedInventoryOf(item);
        item.setStatus(InventoryItemStatus.AVAILABLE);
        inventory.addStock(1);
        // La unidad vuelve al lote EXACTO del que salio. Reponerla en otro no
        // descuadraria ningun total, pero corromperia el costo de la siguiente
        // venta que consumiera de ahi.
        lotService.restoreSaleDetail(saleDetail);
        item.getContainedItems().forEach((child) -> {
            child.setStatus(InventoryItemStatus.UNAVAILABLE);
        });
    }

    private SaleDetail createDetail(InventoryItem item, AddSaleDetailRequestDTO requestDTO) {
        Inventory inventory = item.getInventory();
        PricingOutcome pricing = resolvePricing(
                requestDTO.price(),
                inventory.getPrice(),
                inventory.getCost());
        BigDecimal price = pricing.price();

        // El inventario ya esta BLOQUEADO y es una entidad gestionada: asignarle
        // el precio aqui lo deja en la misma transaccion que la linea, de modo
        // que no puede quedar una venta con precio nuevo y un catalogo sin el.
        if (pricing.becomesCatalogPrice()) {
            inventory.setPrice(pricing.price());
        }

        return SaleDetail.builder()
                .item(item)
                .quantity(1)
                .price(price)
                .product(inventory.getProduct())
                .subTotal(price)
                .invoiceDescription(requestDTO.invoiceDescription())
                .build();
    }


    /**
     * El inventario del item, bloqueado para escritura. Se resuelve por el id
     * del item para que el cerrojo se tome en la MISMA consulta que lo carga.
     */
    private Inventory lockedInventoryOf(InventoryItem item) {
        return inventoryRepository
                .findByItemIdForUpdate(item.getId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el inventario del item especificado"));
    }

}
