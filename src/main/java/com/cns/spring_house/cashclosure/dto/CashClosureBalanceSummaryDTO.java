package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

/** Como cuadro la caja durante el rango. */
public record CashClosureBalanceSummaryDTO(

    long balancedClosures,
    long shortClosures,
    long overClosures,
    /**
     * Suma algebraica de las diferencias: faltantes y sobrantes se compensan a
     * proposito, porque lo que interesa del mes es el descuadre neto.
     */
    BigDecimal netCashDifference

) {

}
