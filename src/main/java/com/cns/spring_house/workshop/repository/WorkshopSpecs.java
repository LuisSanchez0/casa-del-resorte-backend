package com.cns.spring_house.workshop.repository;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.workshop.entity.Workshop;

public class WorkshopSpecs {

    private WorkshopSpecs() {

    }

    public static Specification<Workshop> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.upper(root.get("name")),
                        "%" + name.toUpperCase() + "%");
    }

    public static Specification<Workshop> addressContains(String address) {
        return (root, query, criteriaBuilder) -> address == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("address")),
                        "%" + address.toLowerCase() + "%");
    }

    public static Specification<Workshop> hasActive(Boolean active) {
        return (root, query, criteriaBuilder) -> active == null ? null
                : criteriaBuilder.equal(root.get("isActive"), active);
    }

}
