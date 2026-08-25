package com.cns.spring_house.dailylog.repository;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.dailylog.entity.DailyLog;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DailyLogSpecs {

    public static Specification<DailyLog> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

    public static Specification<DailyLog> performedDateBetween(LocalDate min, LocalDate max) {
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

    public static Specification<DailyLog> hasEmployee(UUID employee) {
        return (root, query, criteriaBuilder) -> employee == null ? null
                : criteriaBuilder.equal(
                        root.get("employee").get("id"),
                        employee);
    }

    /** Busca el registro levantado a partir de una factura concreta. */
    public static Specification<DailyLog> hasSale(UUID sale) {
        return (root, query, criteriaBuilder) -> sale == null ? null
                : criteriaBuilder.equal(
                        root.get("sale").get("id"),
                        sale);
    }

}
