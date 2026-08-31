package com.cns.spring_house.cashclosure.repository.projection;

import java.math.BigDecimal;

/**
 * Los ESCALARES de un mes, sumados por la base de datos sobre los cierres
 * diarios confirmados.
 *
 * Una sola consulta para todo el mes, no una por dia. Sumar treinta cierres en
 * Java significaria treinta viajes y una agregacion que la base ya sabe hacer.
 *
 * Solo se agrega lo que ES agregable. Los saldos pendientes no se suman aqui —
 * cada uno es una foto de un instante, y sumarlos inventaria deuda— y el
 * detalle por articulo o por documento tiene sus propias consultas, porque
 * requiere agrupar y no simplemente sumar.
 */
public interface CashClosureSummaryProjection {

    /** Dias del mes que tienen cierre confirmado. */
    long getClosureCount();

    long getInvoiceCount();

    BigDecimal getInvoiceTotal();

    long getExchangeInvoiceCount();

    BigDecimal getExchangeInvoiceTotal();

    BigDecimal getTotalInvoiced();

    BigDecimal getTotalCollected();

    long getExchangeCollectedCount();

    BigDecimal getExchangeCollectedAmount();

    /** De lo cobrado en el mes, la parte emitida antes de cada dia cerrado. */
    BigDecimal getPreviousExchangeCollectedAmount();

    /** De lo emitido a credito en el mes, lo que seguia pendiente al cerrar. */
    BigDecimal getIssuedExchangePendingAmount();

    BigDecimal getItemsBilledAmount();

    // ── Dinero que salio ─────────────────────────────────────────
    //
    // Suman igual que los cobros y por el mismo motivo: el mes los agrega con
    // UNA consulta sobre `cash_closure`, sin recorrer las tablas hijas.

    long getRefundCount();

    BigDecimal getTotalRefunded();

    long getRefundCashCount();

    BigDecimal getRefundCash();

    long getRefundCardCount();

    BigDecimal getRefundCard();

    long getRefundTransferCount();

    BigDecimal getRefundTransfer();

}
