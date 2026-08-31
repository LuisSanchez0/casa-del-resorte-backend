package com.cns.spring_house.acquisition.repository.projection;

import java.math.BigDecimal;

/**
 * Un punto de la serie de GASTO EN COMPRAS, ya agrupado por la unidad de tiempo
 * que pidio el administrador.
 *
 * {@code bucketDate} es siempre el PRIMER dia del intervalo, normalizado a
 * texto {@code YYYY-MM-DD}. Viaja como TEXTO y no como fecha por el mismo
 * motivo que las demas proyecciones nativas del proyecto: en una consulta
 * nativa la columna llegaria como {@code java.sql.Date}, cuyo
 * {@code toInstant()} lanza {@code UnsupportedOperationException}.
 *
 * {@code amount} es el gasto TOTAL del intervalo y
 * {@code nationalAmount} + {@code importationAmount} su desglose. Los tres
 * viajan juntos para que el cliente pueda pintar la composicion sin pedir la
 * serie tres veces; cuando se filtra por un tipo concreto, el otro vale cero.
 */
public interface AcquisitionBucketProjection {

    String getBucketDate();

    long getAcquisitionCount();

    long getUnitsReceived();

    BigDecimal getAmount();

    BigDecimal getNationalAmount();

    BigDecimal getImportationAmount();

}
