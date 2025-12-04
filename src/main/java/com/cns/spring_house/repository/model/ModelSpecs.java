package com.cns.spring_house.repository.model;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.model.Model;

public class ModelSpecs {
    
    private ModelSpecs() {

    }

    public static Specification<Model> hasBrand(UUID brand) {
        return (root, query, criteriaBuilder) -> brand == null ? null
                : criteriaBuilder.equal(root.get("brand").get("id"), brand);
    }

    public static Specification<Model> hasYear(Integer year) {
        return (root, query, criteriaBuilder) -> year == null ? null
                : criteriaBuilder.equal(root.get("year"), year);
    }

    public static Specification<Model> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")), 
                        "%" + name + "%");
    }

}
