package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SpecialSaleDetailHandler implements SaleDetailHandler {

    private final InventoryItemRepository repository;
    
    @Override
    public FamilyCategory getType() {
        return FamilyCategory.SPECIAL;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SaleDetail addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO) {
        if (requestDTO.item() == null) {
            throw new ValidationException(
                    "El item es obligatorio");
        }
        // Acotado al taller del token: un item de otro taller no debe ser vendible
        // ni siquiera conociendo su UUID.
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
        SaleDetail detail = createDetail(item, requestDTO);
        sale.addDetail(detail);
        return detail;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(SaleDetail saleDetail) {
        InventoryItem item = saleDetail.getItem();
        if (item == null) {
            throw new ValidationException(
                    "El detalle especificado necesita un item");
        }
        if (item.getStatus() != InventoryItemStatus.SOLD) {
            throw new ValidationException(
                    "El item especificado no se encuentra reservado");
        }
        item.setStatus(InventoryItemStatus.AVAILABLE);
        item.getInventory().addStock(1);
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
