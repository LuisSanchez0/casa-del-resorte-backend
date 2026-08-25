package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;

/**
 * Una fila de la agregacion "ventas por dia".
 *
 * La fecha viaja como TEXTO (`YYYY-MM-DD`), igual que
 * {@code EntriesByDayProjection} del dashboard policial: en una consulta
 * nativa la columna llega como {@code java.sql.Date}, cuyo {@code toInstant()}
 * lanza {@code UnsupportedOperationException}, y el servicio ya sabe parsear
 * este formato.
 */
public interface SalesByDayProjection {

    String getSaleDate();

    long getSalesCount();

    BigDecimal getAmount();

}
