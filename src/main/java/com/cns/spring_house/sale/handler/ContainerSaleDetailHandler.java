package com.cns.spring_house.sale.handler;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ContainerSaleDetailHandler implements SaleDetailHandler {
    
    private final InventoryItemRepository repository;

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
        item.setStatus(InventoryItemStatus.SOLD);
        item.getInventory().removeStock(1);
        item.getContainedItems().forEach((child) -> {
            child.setStatus(InventoryItemStatus.SOLD);
        });
        SaleDetail detail = createDetail(item, requestDTO);
        sale.addDetail(detail);
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
        item.setStatus(InventoryItemStatus.AVAILABLE);
        item.getInventory().addStock(1);
        item.getContainedItems().forEach((child) -> {
            child.setStatus(InventoryItemStatus.UNAVAILABLE);
        });
    }

    private SaleDetail createDetail(InventoryItem item, AddSaleDetailRequestDTO requestDTO) {
        BigDecimal price = resolvePrice(
                requestDTO.price(),
                item.getInventory().getPrice(),
                item.getInventory().getCost());
        return SaleDetail.builder()
                .item(item)
                .quantity(1)
                .price(price)
                .product(item.getInventory().getProduct())
                .subTotal(price)
                .invoiceDescription(requestDTO.invoiceDescription())
                .build();
    }

}
