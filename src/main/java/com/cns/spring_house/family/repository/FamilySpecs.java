package com.cns.spring_house.family.repository;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;

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

    public static Specification<Family> requiresModel(Boolean requiresModel) {
        return (root, query, criteriaBuilder) -> requiresModel == null ? null
                : criteriaBuilder.equal(
                        root.get("requiresModel"),
                        requiresModel);
    }

    public static Specification<Family> hasCategory(FamilyCategory category) {
        return (root, query, criteriaBuilder) -> category == null ? null
                : criteriaBuilder.equal(
                        root.get("category"),
                        category);
    }

}
