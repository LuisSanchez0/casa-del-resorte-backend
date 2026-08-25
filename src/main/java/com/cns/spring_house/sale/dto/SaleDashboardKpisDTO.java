package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;

/**
 * Cifras del encabezado del dashboard de ventas, para UN ambito (general o
 * personal).
 *
 * <ul>
 * <li>{@code completedSalesTotal}: ventas COMPLETED historicas, sin
 * REVERSED.</li>
 * <li>{@code completedSalesToday}: las mismas, acotadas a la fecha de negocio
 * de hoy.</li>
 * <li>{@code pendingSales}: ventas abiertas (`SaleStatus.PENDING`), vigentes
 * ahora mismo.</li>
 * <li>{@code pendingExchangeInvoices}: facturas cambiarias completadas y sin
 * cobrar (`COMPLETED` + `EXCHANGE_INVOICE` + `PaymentStatus.PENDING`).</li>
 * <li>{@code amountSoldToday}: suma de {@code Sale.total} de las ventas
 * completadas hoy.</li>
 * <li>{@code averageTicketToday}: {@code amountSoldToday / completedSalesToday},
 * o {@code null} si todavia no hay ninguna venta hoy (dividir por cero no es
 * "cero pesos", es "sin dato").</li>
 * </ul>
 */
public record SaleDashboardKpisDTO(

    long completedSalesTotal,
    long completedSalesToday,
    long pendingSales,
    long pendingExchangeInvoices,
    BigDecimal amountSoldToday,
    BigDecimal averageTicketToday

) {

}
