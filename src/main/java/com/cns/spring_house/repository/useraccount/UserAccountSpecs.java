package com.cns.spring_house.repository.useraccount;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.useraccount.UserAccount;

public class UserAccountSpecs {

    private UserAccountSpecs() {

    }

    public static Specification<UserAccount> hasActive(Boolean active) {
        return (root, query, criteriaBuilder) -> active == null ? null
                : criteriaBuilder.equal(root.get("isActive"), active);
    }

    public static Specification<UserAccount> usernameContains(String username) {
        return (root, query, criteriaBuilder) -> username == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.upper(root.get("username")),
                        "%" + username.toUpperCase() + "%");
    }

    public static Specification<UserAccount> hasRole(UUID role) {
        return (root, query, criteriaBuilder) -> role == null ? null
                : criteriaBuilder.equal(
                        root.get("role").get("id"),
                        role);
    }

    public static Specification<UserAccount> hasEmployee(UUID employee) {
        return (root, query, criteriaBuilder) -> employee == null ? null
                : criteriaBuilder.equal(
                        root.get("employee").get("id"),
                        employee);
    }

}
