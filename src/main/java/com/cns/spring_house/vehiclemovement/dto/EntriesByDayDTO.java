package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDate;

/**
 * Un punto de la tendencia diaria. La serie que se envia es DENSA: el servicio
 * rellena con cero los dias sin entradas para que la grafica no una dos fechas
 * lejanas con una recta y sugiera actividad que no existio.
 */
public record EntriesByDayDTO(

        LocalDate date,
        long entries

) {

}
