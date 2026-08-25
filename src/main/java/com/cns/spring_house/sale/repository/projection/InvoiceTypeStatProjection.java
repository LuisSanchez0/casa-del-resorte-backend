package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;

/**
 * Facturacion de una ventana de tiempo agrupada por tipo de documento, contada
 * por {@code completedAt} —la fecha de EMISION— y no por la de cobro.
 *
 * Es la contrapartida de {@link PaymentMethodStatProjection}, que agrupa lo
 * COBRADO. Una cambiaria emitida hoy y todavia pendiente aparece aqui con su
 * importe integro y no aparece alli en absoluto.
 */
public interface InvoiceTypeStatProjection {

    String getInvoiceType();

    long getSaleCount();

    BigDecimal getAmount();

}
