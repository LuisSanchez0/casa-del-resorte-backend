package com.cns.spring_house.stockitem.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.stockitem.entity.StockItem;

public interface StockItemRepository extends JpaRepository<StockItem, UUID>, JpaSpecificationExecutor<StockItem> {
    
    boolean existsByProductIdAndWorkshopId(UUID product, UUID workshop);

    boolean existsByProductIdAndWorkshopIdAndIdNot(UUID product, UUID workshop, UUID id);

}
