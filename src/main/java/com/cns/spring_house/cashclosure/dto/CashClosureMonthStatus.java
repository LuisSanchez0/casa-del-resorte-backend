package com.cns.spring_house.cashclosure.dto;

/**
 * Si el mes esta cerrado del todo.
 *
 * Existe para no presentar como definitivo un mes al que le faltan dias. Un mes
 * con veinte de treinta cierres tiene cifras reales, pero incompletas, y quien
 * las lee tiene derecho a saberlo.
 */
public enum CashClosureMonthStatus {

    /** Todos los dias del intervalo tienen cierre confirmado. */
    COMPLETE,
    /** Faltan cierres: las cifras describen solo los dias cerrados. */
    PARTIAL;

}
