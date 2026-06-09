package com.cns.spring_house.sale.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.sale.entity.Sale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SaleSpecs {
    
    public static Specification<Sale> nameContains(String customer) {
        return (root, query, criteriaBuilder) -> customer == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.upper(root.get("customer").get("name")),
                        "%" + customer + "%");
    }

    public static Specification<Sale> nitContains(String customer) {
        return (root, query, criteriaBuilder) -> customer == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.upper(root.get("customer").get("nit")),
                        "%" + customer + "%");
    }

    public static Specification<Sale> totalBetween(BigDecimal minTotal, BigDecimal maxTotal) {
        return (root, query, criteriaBuilder) -> {
            if (minTotal != null && maxTotal != null) 
                    return criteriaBuilder.between(root.get("total"), minTotal, maxTotal);
            if (maxTotal != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("total"), maxTotal);
            if (minTotal != null)
                    return criteriaBuilder.greaterThanOrEqualTo(root.get("total"), minTotal);
            return null;
        };
    }

    public static Specification<Sale> completedAtBetween(LocalDateTime minDate, LocalDateTime maxDate) {
        return (root, query, criteriaBuilder) -> {
            if (minDate != null && maxDate != null) 
                    return criteriaBuilder.between(root.get("completedAt"), minDate, maxDate);
            if (maxDate != null)
                    return criteriaBuilder.lessThanOrEqualTo(root.get("completedAt"), maxDate);
            if (minDate != null)
                    return criteriaBuilder.greaterThanOrEqualTo(root.get("completedAt"), minDate);
            return null;
        };
    }

    public static Specification<Sale> hasEmployee(UUID employee) {
        return (root, query, criteriaBuilder) -> employee == null ? null
                : criteriaBuilder.equal(
                        root.get("employee").get("id"),
                        employee);
    }

    public static Specification<Sale> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

}
