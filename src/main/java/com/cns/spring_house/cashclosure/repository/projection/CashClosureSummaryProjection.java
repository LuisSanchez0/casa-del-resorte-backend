package com.cns.spring_house.cashclosure.repository.projection;

import java.math.BigDecimal;

/**
 * Agregado de un rango de cierres, calculado en la base de datos.
 *
 * No aparece {@code pendingExchangeAmount}: ese campo es una FOTO del saldo
 * total en cada cierre, y sumarlo entre dias inventaria deuda que no existe
 * (tres dias con 20.000, 18.000 y 25.000 pendientes darian 63.000). El saldo
 * final del rango se pide aparte, al cierre mas reciente.
 */
public interface CashClosureSummaryProjection {

    long getClosureCount();

    long getInvoiceCount();

    BigDecimal getInvoiceTotal();

    long getExchangeInvoiceCount();

    BigDecimal getExchangeInvoiceTotal();

    BigDecimal getTotalInvoiced();

    long getCashPaymentCount();

    BigDecimal getCashPayments();

    long getCardPaymentCount();

    BigDecimal getCardPayments();

    long getTransferPaymentCount();

    BigDecimal getTransferPayments();

    BigDecimal getTotalCollected();

    long getExchangeCollectedCount();

    BigDecimal getExchangeCollectedAmount();

    long getBalancedClosures();

    long getShortClosures();

    long getOverClosures();

    BigDecimal getNetCashDifference();

}
