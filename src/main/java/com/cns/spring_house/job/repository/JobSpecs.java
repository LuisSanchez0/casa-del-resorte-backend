package com.cns.spring_house.job.repository;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.job.entity.Job;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class JobSpecs {
   
    public static Specification<Job> nameContains(String name) {
        return (root, query, criteriaBuilder) -> name == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.upper(root.get("product").get("name")),
                        "%" + name + "%");
    }

    public static Specification<Job> priceBetween(BigDecimal min, BigDecimal max) {
        return (root, query, criteriaBuilder) -> {
            if (min != null && max != null) 
                    return criteriaBuilder.between(root.get("price"), min, max);
            if (max != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("price"), max);
            if (min != null)
                    return criteriaBuilder.greaterThanOrEqualTo(root.get("price"), min);
            return null;
        };
    }

    public static Specification<Job> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }
    
}
