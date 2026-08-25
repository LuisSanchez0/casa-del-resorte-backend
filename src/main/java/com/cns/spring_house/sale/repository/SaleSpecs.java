package com.cns.spring_house.sale.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;

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

    /**
     * Ventas de un cliente concreto, por identificador.
     *
     * `nameContains`/`nitContains` son una busqueda de texto para la pantalla de
     * historial; no sirven para relacionar: dos clientes pueden compartir un
     * fragmento de nombre, y un nit es un texto que puede estar contenido en
     * otro. Cuando ya se resolvio el Customer, la relacion es por su id.
     */
    public static Specification<Sale> hasCustomer(UUID customer) {
        return (root, query, criteriaBuilder) -> customer == null ? null
                : criteriaBuilder.equal(
                        root.get("customer").get("id"),
                        customer);
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

    public static Specification<Sale> hasStatus(SaleStatus status) {
        return (root, query, criteriaBuilder) -> status == null ? null
                : criteriaBuilder.equal(
                        root.get("status"),
                        status);
    }

    /** Las placas se guardan normalizadas; la busqueda tolera cualquier grafia. */
    public static Specification<Sale> plateContains(String plate) {
        return (root, query, criteriaBuilder) -> {
            if (plate == null || plate.isBlank()) {
                return null;
            }
            String normalized = plate.trim().toLowerCase(Locale.ROOT);
            return criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("plate")),
                    "%" + normalized + "%");
        };
    }

    public static Specification<Sale> hasInvoiceType(InvoiceType invoiceType) {
        return (root, query, criteriaBuilder) -> invoiceType == null ? null
                : criteriaBuilder.equal(
                        root.get("invoiceType"),
                        invoiceType);
    }

    /** Permite listar la cartera pendiente de cobro. */
    public static Specification<Sale> hasPaymentStatus(PaymentStatus paymentStatus) {
        return (root, query, criteriaBuilder) -> paymentStatus == null ? null
                : criteriaBuilder.equal(
                        root.get("paymentStatus"),
                        paymentStatus);
    }

}
