package com.cns.spring_house.cashclosure.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Resumen agregado de un rango de cierres. Se construye SOLO sobre los
 * snapshots persistidos: nunca vuelve a recorrer `sale`, porque el historico ya
 * quedo fijado el dia que se cerro cada caja.
 *
 * El mismo mecanismo sirve para un dia, una semana, una quincena, un mes o un
 * rango arbitrario: un cierre mensual no es otra entidad, es
 * {@code from = 2026-08-01, to = 2026-08-31}.
 *
 * {@code closureCount} es cuantos cierres EXISTEN en el rango, no cuantos dias
 * tiene el rango.
 */
public record CashClosureSummaryResponseDTO(

    LocalDate from,
    LocalDate to,
    long closureCount,
    CashClosureInvoicingDTO invoicing,
    CashClosureCollectionsDTO collections,
    CashClosureRangeReceivablesDTO receivables,
    CashClosureBalanceSummaryDTO balance,
    /** Un elemento por cierre existente, en orden cronologico. */
    List<CashClosureDaySummaryDTO> days

) {

}
