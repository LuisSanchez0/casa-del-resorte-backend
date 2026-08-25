package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;

/**
 * Un punto de la serie de VENTAS GENERADAS, ya agrupado por la unidad de tiempo
 * que pidio el administrador (dia, mes o ano).
 *
 * {@code bucketDate} es siempre el PRIMER dia del intervalo, normalizado a
 * texto {@code YYYY-MM-DD}: {@code 2026-08-15} para un dia, {@code 2026-08-01}
 * para agosto y {@code 2026-01-01} para 2026. Asi una sola proyeccion sirve a
 * las tres granularidades y el servicio sabe reconstruir el intervalo sin
 * volver a preguntar cual era.
 *
 * Viaja como TEXTO por el mismo motivo que {@link SalesByDayProjection}: en una
 * consulta nativa la columna llegaria como {@code java.sql.Date}, cuyo
 * {@code toInstant()} lanza {@code UnsupportedOperationException}.
 */
public interface SalesBucketProjection {

    String getBucketDate();

    long getSalesCount();

    BigDecimal getAmount();

}
