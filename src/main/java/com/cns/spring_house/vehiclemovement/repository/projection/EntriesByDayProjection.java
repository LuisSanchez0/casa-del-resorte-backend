package com.cns.spring_house.vehiclemovement.repository.projection;

/**
 * Una fila de la agregacion "entradas por dia".
 *
 * La fecha viaja como TEXTO (`YYYY-MM-DD`) y no como tipo temporal a proposito:
 * en una consulta nativa la columna llega como {@code java.sql.Date}, cuyo
 * {@code toInstant()} lanza UnsupportedOperationException, y la conversion
 * automatica a {@code LocalDate} no es fiable por esa via. El servicio parsea el
 * texto, que ademas encaja con la estrategia de "hora de pared" del modulo: no
 * hay conversion de zona en ningun punto.
 */
public interface EntriesByDayProjection {

    String getEntryDate();

    long getEntries();

}
