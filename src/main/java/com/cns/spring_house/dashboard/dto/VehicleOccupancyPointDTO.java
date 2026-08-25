package com.cns.spring_house.dashboard.dto;

import java.time.LocalDate;

/**
 * Vehiculos dentro del taller al cerrar un dia concreto.
 *
 * {@code vehicles} es {@code long} y no un decimal: no existe medio vehiculo, y
 * el eje de la grafica se dibuja con enteros.
 */
public record VehicleOccupancyPointDTO(

        LocalDate date,
        long vehicles

) {

}
