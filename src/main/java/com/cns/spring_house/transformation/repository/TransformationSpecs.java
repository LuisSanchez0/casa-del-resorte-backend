package com.cns.spring_house.transformation.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.transformation.entity.Transformation;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TransformationSpecs {
    
    public static Specification<Transformation> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

    public static Specification<Transformation> performedDateBetween(LocalDateTime min, LocalDateTime max) {
        return (root, query, criteriaBuilder) -> {
            if (min != null && max != null) 
                    return criteriaBuilder.between(root.get("performedDate"), min, max);
            if (max != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("performedDate"), max);
            if (min != null)
                    return criteriaBuilder.greaterThanOrEqualTo(root.get("performedDate"), min);
            return null;
        };
    }

    public static Specification<Transformation> hasEmployee(UUID employee) {
        return (root, query, criteriaBuilder) -> employee == null ? null
                : criteriaBuilder.equal(
                        root.get("employee").get("id"),
                        employee);
    }

}
