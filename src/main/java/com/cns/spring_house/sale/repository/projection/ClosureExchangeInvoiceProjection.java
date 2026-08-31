package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Una factura cambiaria con lo que el cierre necesita mostrar de ella.
 *
 * Sirve a los DOS conjuntos —emitidas en el periodo y cobradas en el periodo—
 * porque las columnas son las mismas; lo que cambia es la consulta que la
 * produce, no la forma de la fila.
 *
 * {@code paidAt} y {@code paymentMethod} llegan nulos mientras el documento
 * siga pendiente. {@code customerName} tambien puede ser nulo: una venta de
 * mostrador no lleva cliente.
 */
public interface ClosureExchangeInvoiceProjection {

    UUID getSaleId();

    String getCustomerName();

    LocalDateTime getIssuedAt();

    BigDecimal getAmount();

    /** `PENDING` o `PAID` en el momento en que se lee. */
    String getPaymentStatus();

    LocalDateTime getPaidAt();

    String getPaymentMethod();

}
