package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de clientes por VENTA GENERADA.
 *
 * {@code purchaseCount} cuenta exactamente los documentos que suman en
 * {@code generatedAmount}, no todas las compras del cliente: si contara otras,
 * las dos cifras de la misma fila no se podrian leer juntas.
 *
 * Ambas se calculan sobre ventas COMPLETED sin mirar el estado de cobro, la
 * misma regla que el KPI de monto generado, de modo que los dos son
 * comparables.
 */
public interface CustomerPerformanceProjection {

    UUID getCustomerId();

    String getCustomerName();

    String getCustomerNit();

    long getPurchaseCount();

    BigDecimal getGeneratedAmount();

}
