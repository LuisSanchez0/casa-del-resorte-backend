package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * `workshop` se conserva por compatibilidad con los clientes que ya lo envian,
 * pero el servicio lo IGNORA: el scope se resuelve siempre desde el token. Un
 * parametro de consulta no puede ampliar el alcance de la busqueda.
 */
public record SearchVehicleMovementRequestDTO(

        String plate,
        UUID workshop,
        LocalDateTime entry,
        LocalDateTime exit

) {

}
