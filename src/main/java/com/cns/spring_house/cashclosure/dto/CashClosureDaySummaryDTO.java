package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una fila de la tabla diaria del resumen por rango, leida del snapshot de ese
 * dia.
 *
 * Solo existe fila para los dias que TIENEN cierre. Un dia sin cierre no se
 * rellena con ceros: no hay evidencia de que ese dia se cuadrara la caja, y un
 * cero mentiria diciendo que se cerro sin ventas.
 */
public record CashClosureDaySummaryDTO(

    UUID closureId,
    LocalDate businessDate,
    BigDecimal totalInvoiced,
    BigDecimal totalCollected,
    BigDecimal cashPayments,
    BigDecimal cardPayments,
    BigDecimal transferPayments,
    BigDecimal difference

) {

}
