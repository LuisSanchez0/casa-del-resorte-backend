package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Vehiculo que sigue DENTRO del taller: un movimiento con {@code exit IS NULL}.
 *
 * Solo lleva campos que existen de verdad en el modelo. No hay marca, modelo ni
 * descripcion del vehiculo: el dominio guarda la placa de la visita, no una
 * entidad de vehiculo.
 *
 * {@code registeredBy} es null en los movimientos anteriores a V23, que no
 * tienen actor demostrable; el cliente lo muestra como "No registrado" en lugar
 * de atribuirlo a nadie.
 */
public record CurrentVehicleDTO(

        UUID id,
        String plate,
        LocalDateTime entry,
        String registeredBy,
        boolean registeredByCurrentUser,
        int photos,
        String notes

) {

}
