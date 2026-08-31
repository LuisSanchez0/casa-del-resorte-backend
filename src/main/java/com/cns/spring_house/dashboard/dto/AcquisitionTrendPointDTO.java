package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un punto de la serie de GASTO EN COMPRAS.
 *
 * {@code bucket} es el PRIMER dia del intervalo en las tres granularidades,
 * igual que en la serie de ventas, de modo que el cliente rotula el eje segun la
 * granularidad que pidio y no analizando un formato distinto por caso.
 *
 * {@code amount} es el gasto del intervalo y {@code nationalAmount} +
 * {@code importationAmount} su desglose. Los dos viajan siempre para que la
 * composicion se pueda pintar sin pedir la serie tres veces; al filtrar por un
 * tipo, el otro vale cero y {@code amount} coincide con el que quedo.
 */
public record AcquisitionTrendPointDTO(

        LocalDate bucket,
        long acquisitionCount,
        long unitsReceived,
        BigDecimal amount,
        BigDecimal nationalAmount,
        BigDecimal importationAmount

) { }
