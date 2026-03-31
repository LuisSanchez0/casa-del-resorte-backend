package com.cns.spring_house.inventory.handler;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SpecialInventoryHandler implements InventoryHandler {

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.SPECIAL;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void addStock(Inventory inventory, AddStockRequestDTO requestDTO) {
        if (requestDTO.units() == null) {
            throw new ValidationException(
                    "Las unidades a agregar son necesarias");
        }
        for (int i = 0; i < requestDTO.units(); i++) {
            InventoryItem item = InventoryItem.builder()
                    .status(InventoryItemStatus.AVAILABLE)
                    .build();
            inventory.addItem(item);
        }
        inventory.addStock(requestDTO.units());
    }

}
