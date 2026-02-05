package com.cns.spring_house.inventory.handler;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.QuantityInventory;
import com.cns.spring_house.shared.exception.ValidationException;

@Component
public class QuantityInventoryHandler implements InventoryHandler {

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.NORMAL;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void addStock(Inventory inventory, AddStockRequestDTO requestDTO) {
        if (requestDTO.units() == null) {
            throw new ValidationException(
                    "Las unidades a agregar son necesarias");
        }
        if (inventory.getQuantity() == null) {
            QuantityInventory quantity = QuantityInventory.builder()
                    .units(requestDTO.units())
                    .build();
            inventory.setQuantity(quantity);
            return;
        }
        inventory.getQuantity().addUnits(requestDTO.units());            
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public int getAvailableStock(Inventory inventory) {
        return inventory.getQuantity().getUnits();
    }

}
