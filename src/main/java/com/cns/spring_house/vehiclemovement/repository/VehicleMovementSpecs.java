package com.cns.spring_house.vehiclemovement.repository;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;

public class VehicleMovementSpecs {

    private VehicleMovementSpecs() {

    }

    /**
     * Las placas se guardan en mayusculas, pero la columna se comparaba en
     * minusculas contra el texto crudo del cliente: buscar "P123" no encontraba
     * nada. Se normalizan los dos lados para que la busqueda sea realmente
     * insensible a mayusculas y tolere espacios sobrantes.
     */
    public static Specification<VehicleMovement> plateContains(String plate) {
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

    public static Specification<VehicleMovement> hasWorkshop(UUID workshop) {
        return (root, query, criteriaBuilder) -> workshop == null ? null
                : criteriaBuilder.equal(
                        root.get("workshop").get("id"),
                        workshop);
    }

    public static Specification<VehicleMovement> hasEntry(LocalDateTime entry) {
        return (root, query, criteriaBuilder) -> entry == null ? null
                : criteriaBuilder.equal(root.get("entry"), entry);
    }

    public static Specification<VehicleMovement> hasExit(LocalDateTime exit) {
        return (root, query, criteriaBuilder) -> exit == null ? null
                : criteriaBuilder.equal(root.get("exit"), exit);
    }
    
}

