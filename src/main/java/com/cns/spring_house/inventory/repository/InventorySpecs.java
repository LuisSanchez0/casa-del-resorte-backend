package com.cns.spring_house.inventory.repository;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.inventory.entity.Inventory;

public class InventorySpecs {
    
    private InventorySpecs() {

    }

    public static Specification<Inventory> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

    public static Specification<Inventory> hasProduct(UUID product) {
        return (root, query, criteriaBuilder) -> product == null ? null
                : criteriaBuilder.equal(
                        root.get("product").get("id"),
                        product);
    }

}
