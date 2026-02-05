package com.cns.spring_house.inventory.handler;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;

public interface InventoryHandler {

    FamilyCategory getType();
    
    void addStock(Inventory inventory, AddStockRequestDTO requestDTO);

    int getAvailableStock(Inventory inventory);

}
