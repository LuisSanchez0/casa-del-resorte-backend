package com.cns.spring_house.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.inventory.entity.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, UUID>, JpaSpecificationExecutor<Inventory> {
    
    boolean existsByProductIdAndWorkshopId(UUID product, UUID workshop);

    boolean existsByProductIdAndWorkshopIdAndIdNot(UUID product, UUID workshop, UUID id);

}
