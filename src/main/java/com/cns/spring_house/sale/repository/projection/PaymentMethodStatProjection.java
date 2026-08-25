package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;

/**
 * Cobros de hoy agrupados por forma de pago. Solo agrega ventas realmente
 * cobradas (`paymentStatus = PAID`, `paidAt` de hoy): una cambiaria pendiente
 * nunca aporta a ningun metodo.
 */
public interface PaymentMethodStatProjection {

    String getMethod();

    long getSaleCount();

    BigDecimal getAmount();

}
