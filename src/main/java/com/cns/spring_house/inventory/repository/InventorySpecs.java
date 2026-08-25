package com.cns.spring_house.inventory.repository;

import java.math.BigDecimal;
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

    public static Specification<Inventory> isLowStock(Boolean isLowStock) {
        return (root, query, criteriaBuilder) -> {
            if (isLowStock == null) {
                return null;
            }
            if (isLowStock) {
                return criteriaBuilder.lessThanOrEqualTo(
                        root.get("stock"),
                        root.get("minStock"));
            }
            return criteriaBuilder.greaterThan(
                    root.get("stock"),
                    root.get("minStock"));
        };
    }

    public static Specification<Inventory> priceBetween(BigDecimal minPrice, BigDecimal maxPrice) {
        return (root, query, criteriaBuilder) -> {
            if (minPrice != null && maxPrice != null) 
                    return criteriaBuilder.between(root.get("price"), minPrice, maxPrice);
            if (maxPrice != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("price"), maxPrice);
            if (minPrice != null)
                return criteriaBuilder.greaterThanOrEqualTo(
                        root.get("price"), minPrice);
            return null;
        };
    }

    public static Specification<Inventory> costBetween(BigDecimal minCost, BigDecimal maxCost) {
        return (root, query, criteriaBuilder) -> {
            if (minCost != null && maxCost != null) 
                    return criteriaBuilder.between(root.get("cost"), minCost, maxCost);
            if (maxCost != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("cost"), maxCost);
            if (minCost != null)
                return criteriaBuilder.greaterThanOrEqualTo(
                        root.get("cost"), minCost);
            return null;
        };
    }

}
