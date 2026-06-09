package com.cns.spring_house.sale.handler;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryRepository;
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
    
    @Override
    public FamilyCategory getType() {
        return FamilyCategory.NORMAL;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addDetail(Sale sale, AddSaleDetailRequestDTO requestDTO) {
        Inventory inventory = inventoryRepository
                .findByProductIdAndWorkshopId(
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
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeDetail(Sale sale, SaleDetail saleDetail) {
        Inventory inventory = inventoryRepository
                .findByProductIdAndWorkshopId(
                        saleDetail.getProduct().getId(),
                        SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe un inventario para el producto seleccionado"));
        inventory.addStock(saleDetail.getQuantity());
        sale.removeDetail(saleDetail);
    }

    private SaleDetail createDetail(Inventory inventory, AddSaleDetailRequestDTO requestDTO) {
        BigDecimal price = Optional
                .ofNullable(requestDTO.price())
                .orElse(inventory.getPrice());
        BigDecimal subTotal = price.multiply(
                BigDecimal.valueOf(requestDTO.quantity()));
        return SaleDetail.builder()
                .quantity(requestDTO.quantity())
                .price(price)
                .product(inventory.getProduct())
                .subTotal(subTotal)
                .build();
    }

}
