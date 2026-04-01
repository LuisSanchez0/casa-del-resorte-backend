package com.cns.spring_house.vehiclemovement.repository;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;

public class VehicleMovementSpecs {

    private VehicleMovementSpecs() {

    }

    public static Specification<VehicleMovement> plateContains(String plate) {
        return (root, query, criteriaBuilder) -> plate == null ? null
                : criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("plate")),
                        "%" + plate + "%");
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

