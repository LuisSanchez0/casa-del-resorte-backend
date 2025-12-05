package com.cns.spring_house.repository.attribute;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.model.attribute.Attribute;
import com.cns.spring_house.model.attribute.AttributeDataType;

public class AttributeSpecs {
    
    private AttributeSpecs() {

    }

    public static Specification<Attribute> hasFamily(UUID family) {
        return (root, query, criteriaBuilder) -> family == null ? null
                : criteriaBuilder.equal(
                        root.get("family").get("id"),
                        family);
    }

    public static Specification<Attribute> keyContains(String key) {
        return (root, query, criteriaBuilder) -> key == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("key")), 
                        "%" + key + "%");
    }

    public static Specification<Attribute> hasDataType(AttributeDataType dataType) {
        return (root, query, criteriaBuilder) -> dataType == null ? null
                : criteriaBuilder.equal(
                        root.get("dataType"),
                        dataType);
    }

    public static Specification<Attribute> hasRequired(Boolean required) {
        return (root, query, criteriaBuilder) -> required == null ? null
                : criteriaBuilder.equal(
                        root.get("required"),
                        required);
    }

}
