package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

/** Cartera cambiaria en el momento del calculo o del cierre. */
public record CashClosureReceivablesDTO(

    /** Cambiarias cobradas HOY, sea cual sea el dia en que se emitieron. */
    long collectedCount,
    BigDecimal collectedAmount,
    /**
     * De {@code collectedAmount}, la parte emitida en dias anteriores: el cobro
     * de cartera vieja, que no aporta nada a la facturacion de hoy.
     */
    BigDecimal previousCollectedAmount,
    /**
     * Saldo pendiente TOTAL del taller, no solo el generado hoy: es lo que el
     * taller tiene por cobrar en ese instante.
     */
    BigDecimal pendingAmount

) {

}
