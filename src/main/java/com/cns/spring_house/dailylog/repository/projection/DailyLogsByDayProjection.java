package com.cns.spring_house.dailylog.repository.projection;

import java.time.LocalDate;

/**
 * Una fila de la agregacion "bitacoras por dia".
 *
 * A diferencia de {@code SalesByDayProjection}, la fecha viaja como
 * {@link LocalDate} y no como texto: {@code DailyLog.performedDate} ya es una
 * columna DATE, asi que la consulta es JPQL y no hace falta truncar un
 * timestamp ni esquivar el {@code java.sql.Date} que devolveria una nativa.
 *
 * Solo aparecen los dias CON actividad; densificar la serie es cosa del
 * servicio, igual que en el dashboard de ventas.
 */
public interface DailyLogsByDayProjection {

    LocalDate getLogDate();

    long getLogCount();

}
