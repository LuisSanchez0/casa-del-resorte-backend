package com.cns.spring_house.product.repository;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.product.entity.Product;

import jakarta.persistence.criteria.Expression;

public class ProductSpecs {

    private ProductSpecs() {

    }

    public static Specification<Product> hasFamily(UUID family) {
        return (root, query, criteriaBuilder) -> family == null ? null
                : criteriaBuilder.equal(
                        root.get("family").get("id"),
                        family);
    }

    public static Specification<Product> hasModel(UUID model) {
        return (root, query, criteriaBuilder) -> model == null ? null
                : criteriaBuilder.equal(
                        root.get("model").get("id"),
                        model);
    }

    public static Specification<Product> hasAttribute(String key, Object value) {
        return (root, query, criteriaBuilder) -> {
            Expression<String> jsonb = root.get("attributes");
            return criteriaBuilder.equal(
                    criteriaBuilder.function(
                            "jsonb_extract_path_text",
                            String.class,
                            jsonb,
                            criteriaBuilder.literal(key)),
                    value);
        };
    }

    public static Specification<Product> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + name + "%");
    }

    public static Specification<Product> skuContains(String sku) {
        return (root, query, criteriaBuilder) -> sku == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("sku")),
                        "%" + sku + "%");
    }

}
