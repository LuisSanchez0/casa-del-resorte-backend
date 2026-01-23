package com.cns.spring_house.role.repository;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.role.entity.Role;

public class RoleSpecs {

    private RoleSpecs() {

    }

    public static Specification<Role> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.upper(root.get("name")),
                        "%" + name.toUpperCase() + "%");
    }

}
