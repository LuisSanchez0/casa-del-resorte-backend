package com.cns.spring_house.inventory.handler;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.UnitInventory;
import com.cns.spring_house.inventory.entity.UnitInventoryStatus;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.repository.UnitInventoryRepository;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UnitInventoryHandler implements InventoryHandler {

    private final InventoryRepository repository;
    private final UnitInventoryRepository unitInventoryRepository;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.SPECIAL;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void addStock(Inventory inventory, AddStockRequestDTO requestDTO) {
        UnitInventory unit = UnitInventory.builder()
                .status(UnitInventoryStatus.AVAILABLE)
                .build();
        inventory.addUnit(unit);
        if (Boolean.TRUE.equals(inventory.getIsBundle())) {
            if (requestDTO.children() == null || requestDTO.children().isEmpty()) {
                throw new ValidationException(
                        "Es necesaria una lista de subproductos");
            }
            addChildren(unit, requestDTO.children());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public int getAvailableStock(Inventory inventory) {
        return unitInventoryRepository
                .countByInventoryAndStatus(
                        inventory,
                        UnitInventoryStatus.AVAILABLE);
    }

    private void addChildren(UnitInventory parent, List<UUID> children) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        for (UUID productId : children) {
            repository.findByProductIdAndWorkshopId(productId, workshopId)
                    .ifPresent(inventory -> {
                        FamilyCategory childCategory = inventory
                                .getProduct()
                                .getFamily()
                                .getCategory();
                        if (childCategory == FamilyCategory.SPECIAL) {
                            UnitInventory unit = UnitInventory.builder()
                                    .status(UnitInventoryStatus.UNAVAILABLE)
                                    .build();
                            parent.addChild(unit);
                            inventory.addUnit(unit);
                        }
                    });
        }
    }

}
