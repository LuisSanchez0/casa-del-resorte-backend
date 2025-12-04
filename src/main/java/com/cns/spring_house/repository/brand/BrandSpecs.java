package com.cns.spring_house.repository.brand;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.brand.Brand;

public class BrandSpecs {

    private BrandSpecs() {
        
    }
    
    public static Specification<Brand> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")),
                        "%" + name.toLowerCase() + "%");
    }

}
