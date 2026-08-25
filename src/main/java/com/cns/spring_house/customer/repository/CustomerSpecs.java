package com.cns.spring_house.customer.repository;

import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.customer.entity.Customer;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CustomerSpecs {
    
    /**
     * Los dos lados se normalizan a minusculas. Antes la columna se pasaba a
     * minusculas y el termino a MAYUSCULAS, de modo que ningun nombre alfabetico
     * podia coincidir: buscar "cliente" o "CLIENTE" devolvia siempre vacio
     * aunque el cliente existiera.
     *
     * Locale.ROOT y no el del servidor: en locales como el turco 'I' no pasa a
     * 'i', y la busqueda cambiaria segun donde se despliegue. Es el mismo
     * criterio que ya usa VehicleMovementSpecs con las placas.
     */
    public static Specification<Customer> nameContains(String name) {
        return (root, query, criteriaBuilder) -> {
            if (name == null || name.isBlank()) {
                return null;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            return criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("name")),
                    "%" + normalized + "%");
        };
    }

    public static Specification<Customer> hasNit (String nit) {
        return (root, query, criteriaBuilder) -> nit == null ? null
                : criteriaBuilder.equal(root.get("nit"), nit);
    }

}
