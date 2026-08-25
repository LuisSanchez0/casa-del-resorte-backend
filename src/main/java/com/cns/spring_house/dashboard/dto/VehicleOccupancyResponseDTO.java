package com.cns.spring_house.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Serie de ocupacion del taller.
 *
 * {@code currentVehicles} viaja junto a la serie para que la tarjeta pueda
 * contrastar "ahora mismo" con la tendencia sin una segunda peticion. Por
 * construccion de la consulta coincide con el ultimo punto cuando el periodo
 * llega hasta hoy.
 *
 * {@code peakVehicles} es el maximo de la serie devuelta, calculado en el
 * servidor sobre los mismos puntos que se pintan.
 */
public record VehicleOccupancyResponseDTO(

        LocalDate from,
        LocalDate to,
        long currentVehicles,
        long peakVehicles,
        List<VehicleOccupancyPointDTO> points

) {

}
