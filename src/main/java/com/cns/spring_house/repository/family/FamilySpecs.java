package com.cns.spring_house.repository.family;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.family.Family;

public class FamilySpecs {
    
    private FamilySpecs() {

    }

    public static Specification<Family> codeContains(String code) {
        return (root, query, criteriaBuilder) -> code == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("code")), 
                        "%" + code + "%");
    }

    public static Specification<Family> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")), 
                        "%" + name + "%");
    }

}
