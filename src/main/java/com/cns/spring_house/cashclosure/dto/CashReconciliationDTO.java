package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

import com.cns.spring_house.cashclosure.entity.CashBalanceStatus;

/**
 * Arqueo de efectivo. Solo cuenta el efectivo fisico: tarjeta y transferencia
 * entraron al banco, no al cajon, y sumarlas al esperado convertiria cualquier
 * caja correcta en un faltante enorme.
 */
public record CashReconciliationDTO(

    /** Fondo con el que arranco el dia; lo declara la persona. */
    BigDecimal openingCash,
    /** {@code openingCash + cobros en efectivo del dia}. */
    BigDecimal expectedCash,
    /** Efectivo realmente contado; lo declara la persona. */
    BigDecimal countedCash,
    /** {@code countedCash - expectedCash}. Negativa = faltante. */
    BigDecimal difference,
    /** Derivado del signo de {@code difference}; nunca se persiste. */
    CashBalanceStatus status

) {

}
