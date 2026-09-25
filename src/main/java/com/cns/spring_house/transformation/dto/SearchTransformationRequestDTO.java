package com.cns.spring_house.transformation.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Filtros de busqueda del historial de transformaciones.
 *
 * NO declara `workshop` a proposito: el alcance por taller sale del token
 * (`SecurityUtils.getWorkshopId()`), no de quien pregunta. Mientras fue un
 * parametro, omitirlo devolvia el historial de TODOS los talleres y ponerlo con
 * el UUID de otro devolvia el de ese otro. Mismo criterio que
 * `SearchInventoryRequestDTO`.
 */
public record SearchTransformationRequestDTO(

    UUID employee,
    LocalDateTime minDate,
    LocalDateTime maxDate

) {
    
}
