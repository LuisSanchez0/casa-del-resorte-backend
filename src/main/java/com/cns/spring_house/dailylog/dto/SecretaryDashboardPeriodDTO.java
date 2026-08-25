package com.cns.spring_house.dailylog.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resumen del periodo seleccionado, agregado sobre la MISMA serie que pinta la
 * grafica: no hay una segunda consulta que pudiera contar algo distinto de lo
 * que se ve.
 *
 * Ambos extremos son inclusivos y {@code to} es siempre la fecha de negocio.
 *
 * {@code averageMyDailyLogs} divide entre los dias del periodo —{@code days}—,
 * no entre los dias con actividad: promediar solo sobre los dias trabajados
 * daria una cifra mas alta que no responde a "cuantas bitacoras por dia salen
 * en estos 30 dias". El denominador se expone junto al cociente precisamente
 * para que no haya que adivinarlo.
 *
 * {@code bestDay} es null cuando no hubo ni una bitacora propia en el periodo;
 * en ese caso {@code bestDayCount} es cero. No se inventa un "mejor dia" entre
 * empates a cero.
 */
public record SecretaryDashboardPeriodDTO(

        LocalDate from,
        LocalDate to,
        int days,
        long totalSales,
        long myDailyLogs,
        int activeDays,
        BigDecimal averageMyDailyLogs,
        LocalDate bestDay,
        long bestDayCount

) {

}
