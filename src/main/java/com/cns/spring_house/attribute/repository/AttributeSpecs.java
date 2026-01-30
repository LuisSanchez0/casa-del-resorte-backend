package com.cns.spring_house.attribute.repository;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.attribute.entity.Attribute;
import com.cns.spring_house.attribute.entity.AttributeDataType;

public class AttributeSpecs {
    
    private AttributeSpecs() {

    }

    public static Specification<Attribute> hasFamily(UUID family) {
        return (root, query, criteriaBuilder) -> family == null ? null
                : criteriaBuilder.equal(
                        root.get("family").get("id"),
                        family);
    }

    public static Specification<Attribute> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("name")), 
                        "%" + name + "%");
    }

    public static Specification<Attribute> hasDataType(AttributeDataType dataType) {
        return (root, query, criteriaBuilder) -> dataType == null ? null
                : criteriaBuilder.equal(
                        root.get("dataType"),
                        dataType);
    }

    public static Specification<Attribute> isRequired(Boolean isRequired) {
        return (root, query, criteriaBuilder) -> isRequired == null ? null
                : criteriaBuilder.equal(
                        root.get("isRequired"),
                        isRequired);
    }

}
