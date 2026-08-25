package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;

/**
 * Conteo e importe de ventas completadas en una ventana de tiempo. Se usa para
 * el KPI "Ventas de hoy": una sola consulta agregada, sin cargar entidades.
 */
public interface SalesSummaryProjection {

    long getSalesCount();

    BigDecimal getAmount();

}
