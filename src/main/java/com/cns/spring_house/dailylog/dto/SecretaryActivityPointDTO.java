package com.cns.spring_house.dailylog.dto;

import java.time.LocalDate;

/**
 * Un dia de la serie del dashboard de secretaria. La serie llega DENSA: los
 * dias sin ventas ni bitacoras valen cero y estan, no se omiten.
 *
 * Omitirlos uniria dos fechas lejanas con una recta y aparentaria actividad
 * intermedia que nunca existio.
 */
public record SecretaryActivityPointDTO(

        LocalDate date,
        long salesCount,
        long myDailyLogsCount

) {

}
