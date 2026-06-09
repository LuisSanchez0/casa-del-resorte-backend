package com.cns.spring_house.customer.repository;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.customer.entity.Customer;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CustomerSpecs {
    
    public static Specification<Customer> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + name.toUpperCase() + "%");
    }

    public static Specification<Customer> hasNit (String nit) {
        return (root, query, criteriaBuilder) -> nit == null ? null
                : criteriaBuilder.equal(root.get("nit"), nit);
    }

}
