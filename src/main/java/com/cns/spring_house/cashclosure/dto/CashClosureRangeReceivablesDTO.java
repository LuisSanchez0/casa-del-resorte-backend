package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

/**
 * Cartera cambiaria de un RANGO de cierres.
 *
 * Lo cobrado si se suma dia a dia: son cobros distintos. El saldo pendiente NO:
 * {@code pendingExchangeAmount} es una foto del saldo total en cada cierre, asi
 * que sumar los de tres dias (20.000 + 18.000 + 25.000) daria 63.000 de deuda
 * inexistente. Por eso {@code endingPendingAmount} es el saldo del cierre
 * cronologicamente MAS RECIENTE del rango, no una suma.
 */
public record CashClosureRangeReceivablesDTO(

    long collectedCount,
    BigDecimal collectedAmount,
    BigDecimal endingPendingAmount

) {

}
