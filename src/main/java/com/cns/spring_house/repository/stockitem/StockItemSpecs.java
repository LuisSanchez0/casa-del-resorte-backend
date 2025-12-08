package com.cns.spring_house.repository.stockitem;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.stockitem.StockItem;

public class StockItemSpecs {
    
    private StockItemSpecs() {

    }

    public static Specification<StockItem> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

    public static Specification<StockItem> hasProduct(UUID product) {
        return (root, query, criteriaBuilder) -> product == null ? null
                : criteriaBuilder.equal(
                        root.get("product").get("id"),
                        product);
    }

}
