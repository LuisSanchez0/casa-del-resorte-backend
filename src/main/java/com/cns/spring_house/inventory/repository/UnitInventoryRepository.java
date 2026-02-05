package com.cns.spring_house.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.UnitInventory;
import com.cns.spring_house.inventory.entity.UnitInventoryStatus;

public interface UnitInventoryRepository extends JpaRepository<UnitInventory, UUID> {
    
    int countByInventoryAndStatus(Inventory inventory, UnitInventoryStatus status);

}
