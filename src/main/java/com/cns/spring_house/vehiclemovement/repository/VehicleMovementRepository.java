package com.cns.spring_house.vehiclemovement.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;

public interface VehicleMovementRepository extends JpaRepository<VehicleMovement, UUID>, JpaSpecificationExecutor<VehicleMovement> {
    
    boolean existsByPlateAndExitIsNull(String plate);

}
