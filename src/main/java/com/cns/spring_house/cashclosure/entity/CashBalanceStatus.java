package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;

/**
 * Resultado del arqueo, DERIVADO del signo de la diferencia. No se persiste ni
 * se acepta del cliente: guardarlo permitiria que un cierre dijera CUADRADA con
 * una diferencia de -20, y ese estado imposible no puede existir si el unico
 * sitio donde se decide es {@link #of(BigDecimal)}.
 */
public enum CashBalanceStatus {

    /** El efectivo contado coincide con el esperado. */
    BALANCED,
    /** Se conto MENOS efectivo del esperado (diferencia negativa). */
    SHORT,
    /** Se conto MAS efectivo del esperado (diferencia positiva). */
    OVER;

    public static CashBalanceStatus of(BigDecimal difference) {
        return switch (Integer.signum(difference.signum())) {
            case 0 -> BALANCED;
            case -1 -> SHORT;
            default -> OVER;
        };
    }

}
