package com.cns.spring_house.vehiclemovement.repository;

import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.cns.spring_house.vehiclemovement.entity.VehicleMechanicAssignmentEvent;

/**
 * Solo insercion a proposito: extiende {@link Repository} y expone unicamente
 * {@code saveAndFlush}, asi que no hay metodo de actualizacion ni de borrado
 * que alguien pueda llamar por descuido. La lectura de la bitacora va por
 * {@code VehicleMechanicHistoryService} con una proyeccion JDBC.
 */
public interface VehicleMechanicAssignmentEventRepository extends Repository<VehicleMechanicAssignmentEvent, UUID> {
    <S extends VehicleMechanicAssignmentEvent> S saveAndFlush(S event);
}
