package com.cns.spring_house.vehiclemovement.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Vehiculo dentro del taller, leido sin materializar la entidad ni su coleccion
 * de fotografias: `photos` llega como conteo agregado y el empleado por LEFT
 * JOIN, de modo que la lista completa se resuelve en una sola consulta.
 */
public interface CurrentVehicleProjection {

    UUID getId();

    String getPlate();

    LocalDateTime getEntry();

    /** Null en los movimientos anteriores a V23, sin actor demostrable. */
    UUID getEmployeeId();

    String getFirstName();

    String getLastName();

    int getPhotos();

    String getNotes();

}
