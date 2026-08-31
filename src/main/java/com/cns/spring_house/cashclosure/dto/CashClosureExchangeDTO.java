package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Las facturas cambiarias del periodo, en sus DOS papeles.
 *
 * Los dos conjuntos son distintos y ninguno contiene al otro:
 *
 * <pre>
 * EMITIDAS   se facturaron a credito en el periodo. Pendientes o no.
 * COBRADAS   entro su dinero en el periodo. Pudieron emitirse hace un mes.
 * </pre>
 *
 * Una cambiaria emitida y cobrada el mismo dia sale en las dos listas, y eso es
 * correcto: son dos hechos distintos sobre el mismo documento. Su dinero se
 * cuenta UNA sola vez porque el total cobrado no se suma de aqui, sino del
 * desglose por forma de pago.
 *
 * {@code issuedPendingAmount} es lo que quedo por cobrar DE LO EMITIDO EN EL
 * PERIODO, no la cartera historica del taller: esa es otra pregunta y no
 * pertenece al cierre de un dia.
 */
public record CashClosureExchangeDTO(

    long issuedCount,
    BigDecimal issuedAmount,
    /** De lo emitido en el periodo, lo que seguia sin cobrarse. */
    BigDecimal issuedPendingAmount,

    long collectedCount,
    BigDecimal collectedAmount,
    /** De lo cobrado en el periodo, la parte emitida ANTES del periodo. */
    BigDecimal previousCollectedAmount,

    List<ClosureExchangeInvoiceDTO> issued,
    List<ClosureExchangeInvoiceDTO> collected

) { }
