package com.cns.spring_house.repository.stockitem;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.model.stockitem.StockItem;

public interface StockItemRepository extends JpaRepository<StockItem, UUID>, JpaSpecificationExecutor<StockItem> {
    
    boolean existsByProductIdAndWorkshopId(UUID product, UUID workshop);

    boolean existsByProductIdAndWorkshopIdAndIdNot(UUID product, UUID workshop, UUID id);

    Optional<StockItem> findByProductIdAndWorkshopId(UUID product, UUID workshop);

}
