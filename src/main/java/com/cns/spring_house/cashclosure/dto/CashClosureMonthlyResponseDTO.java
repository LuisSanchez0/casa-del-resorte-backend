package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cierre MENSUAL: la misma estructura contable que un dia, agregada sobre los
 * cierres diarios confirmados del mes.
 *
 * Se construye desde los SNAPSHOTS y no desde `sale`. Es lo que preserva la
 * verdad historica: si el mes se recalculara contra las ventas actuales, cobrar
 * hoy una cambiaria de marzo cambiaria el cierre mensual de marzo, que ya se
 * habia dado por bueno.
 *
 * {@code status} no se puede omitir. Un mes con veinte de treinta dias cerrados
 * NO es un mes cerrado, y presentarlo como definitivo invitaria a tomar
 * decisiones sobre una cifra incompleta.
 */
public record CashClosureMonthlyResponseDTO(

    int year,
    int month,
    /** Primer dia del mes. */
    LocalDate from,
    /** Ultimo dia del mes que YA ocurrio; en el mes en curso, la fecha de hoy. */
    LocalDate to,

    // ── Completitud ──────────────────────────────────────────────

    /** Dias del intervalo que ya tienen cierre confirmado. */
    long closedDays,
    /** Dias del intervalo que aun no lo tienen. */
    long pendingDays,
    CashClosureMonthStatus status,

    // ── Misma estructura que el diario ───────────────────────────

    List<ClosureSoldItemDTO> items,
    BigDecimal itemsBilledAmount,
    CashClosureInvoicingDTO invoicing,
    CashClosureExchangeDTO exchange,
    CashClosureCollectionsDTO collections,

    /** Lo devuelto en el mes, por la fecha en que el dinero salio. */
    CashClosureRefundsDTO refunds,

    /** `totalCollected - totalRefunded` del mes. Puede ser negativo. */
    BigDecimal netPeriod

) { }
