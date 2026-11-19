package com.cns.spring_house.vehiclemovement.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Una asignacion real del mecanico responsable de UNA visita (V47).
 * <p>
 * Es historia, no estado: el mecanico vigente sigue siendo
 * {@code VehicleMovement.mechanic}. Solo se inserta, dentro de la misma
 * transaccion que cambia la visita; {@code @Immutable} impide que Hibernate la
 * actualice y un trigger de la base rechaza UPDATE y DELETE.
 * <p>
 * Los UUID son la identidad. Desde V48 tambien guarda los nombres visibles tal
 * como eran AL MOMENTO del evento (anterior, nuevo y quien lo registro) y el
 * motivo de cada reasignacion; un cambio de nombre posterior no los altera. Los
 * eventos anteriores a V48 tienen esas columnas en NULL y no se reconstruyen.
 */
@Entity
@Immutable
@Table(name = "vehicle_mechanic_assignment_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleMechanicAssignmentEvent {

    public enum Type {
        /** Mecanico elegido al registrar el ingreso. */
        INITIAL_ASSIGNMENT,
        /** Cambio posterior antes de iniciar el trabajo (o primera asignacion de una visita historica). */
        REASSIGNMENT
    }

    @Id
    @UuidGenerator
    private UUID id;
    @Column(nullable = false, updatable = false)
    private UUID vehicleMovementId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Type eventType;
    @Column(updatable = false)
    private UUID previousMechanicUserId;
    @Column(nullable = false, updatable = false)
    private UUID newMechanicUserId;
    @Column(nullable = false, updatable = false)
    private UUID changedByUserId;
    @Column(nullable = false, updatable = false)
    private Instant changedAt;
    @Column(updatable = false)
    private String previousMechanicNameAtEvent;
    @Column(updatable = false)
    private String newMechanicNameAtEvent;
    @Column(updatable = false)
    private String actorNameAtEvent;
    @Column(updatable = false)
    private String reassignmentReason;

    /** Nombre visible de una cuenta en este instante, en el formato de la aplicacion ("Nombre Apellido"). */
    public record Person(UUID id, String name) {}

    public VehicleMechanicAssignmentEvent(UUID vehicleMovementId, Type eventType, Person previousMechanic,
            Person newMechanic, Person actor, Instant changedAt, String reassignmentReason) {
        this.vehicleMovementId = vehicleMovementId;
        this.eventType = eventType;
        this.previousMechanicUserId = previousMechanic == null ? null : previousMechanic.id();
        this.previousMechanicNameAtEvent = previousMechanic == null ? null : previousMechanic.name();
        this.newMechanicUserId = newMechanic.id();
        this.newMechanicNameAtEvent = newMechanic.name();
        this.changedByUserId = actor.id();
        this.actorNameAtEvent = actor.name();
        this.changedAt = changedAt;
        this.reassignmentReason = reassignmentReason;
    }
}
