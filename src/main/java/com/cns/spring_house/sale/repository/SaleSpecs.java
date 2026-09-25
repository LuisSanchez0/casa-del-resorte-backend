package com.cns.spring_house.sale.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class SaleSpecs {
    
    /**
     * Nombre del cliente registrado O el escrito en la propia venta: una venta
     * a Consumidor Final o guardada sin definir no tiene Customer, y sin el
     * segundo termino buscarla por su nombre de referencia no la encontraria.
     * En una venta con NIT ya cerrada el segundo es el nombre congelado, asi
     * que se encuentra tambien por el nombre con el que se facturo.
     *
     * Los nombres se guardan en mayusculas, asi que el termino tambien se sube:
     * antes se comparaba tal cual y "juan" nunca encontraba a "JUAN".
     */
    public static Specification<Sale> nameContains(String customer) {
        return (root, query, criteriaBuilder) -> {
            if (customer == null) {
                return null;
            }
            String pattern = "%" + customer.toUpperCase(Locale.ROOT) + "%";
            return criteriaBuilder.or(
                    criteriaBuilder.like(
                            criteriaBuilder.upper(customerJoin(root).get("name")),
                            pattern),
                    criteriaBuilder.like(
                            criteriaBuilder.upper(root.get("customerName")),
                            pattern));
        };
    }

    /** NIT actual del cliente O el congelado en la venta al cerrarla. */
    public static Specification<Sale> nitContains(String customer) {
        return (root, query, criteriaBuilder) -> {
            if (customer == null) {
                return null;
            }
            String pattern = "%" + customer.toUpperCase(Locale.ROOT) + "%";
            return criteriaBuilder.or(
                    criteriaBuilder.like(
                            criteriaBuilder.upper(customerJoin(root).get("nit")),
                            pattern),
                    criteriaBuilder.like(
                            criteriaBuilder.upper(root.get("customerNit")),
                            pattern));
        };
    }

    /**
     * Guardadas para despues (`true`) frente a carritos abiertos que nunca se
     * guardaron (`false`). Null no filtra.
     */
    public static Specification<Sale> isSavedForLater(Boolean savedForLater) {
        return (root, query, criteriaBuilder) -> savedForLater == null ? null
                : criteriaBuilder.equal(
                        root.get("savedForLater"),
                        savedForLater);
    }

    /**
     * LEFT y compartido. `root.get("customer")` genera un INNER JOIN implicito,
     * que descartaba de la busqueda toda venta sin Customer; y cada spec que lo
     * pidiera por separado anadiria otro JOIN a la misma tabla.
     */
    private static Join<Sale, Customer> customerJoin(Root<Sale> root) {
        for (Join<Sale, ?> join : root.getJoins()) {
            if (join.getAttribute().getName().equals("customer")
                    && join.getJoinType() == JoinType.LEFT) {
                @SuppressWarnings("unchecked")
                Join<Sale, Customer> existing = (Join<Sale, Customer>) join;
                return existing;
            }
        }
        return root.join("customer", JoinType.LEFT);
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
