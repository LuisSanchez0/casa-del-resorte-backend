package com.cns.spring_house.repository.role;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.role.Role;

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
