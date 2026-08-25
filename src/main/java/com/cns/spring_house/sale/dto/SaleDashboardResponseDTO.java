package com.cns.spring_house.sale.dto;

import java.time.LocalDate;

/**
 * Respuesta completa de {@code GET /v1/sales/dashboard}, con los DOS ambitos
 * en UNA sola peticion: alternar entre "General" y "Mis estadisticas" en la
 * interfaz no debe volver a consultar el servidor.
 *
 * Todo viene acotado al taller del token. El ambito personal se resuelve
 * tambien en el servidor a partir del empleado autenticado: el cliente no
 * envia (ni puede enviar) un identificador de empleado o de taller.
 *
 * {@code personalScopeAvailable} es false cuando la cuenta autenticada no
 * tiene empleado asociado; en ese caso {@code mine} llega en ceros y la
 * interfaz desactiva el ambito personal en lugar de mostrar cifras vacias
 * como si fueran reales.
 */
public record SaleDashboardResponseDTO(

    SaleDashboardStatsDTO general,
    SaleDashboardStatsDTO mine,
    boolean personalScopeAvailable,
    /** Fecha de negocio usada para los KPI de "hoy", resuelta en el servidor. */
    LocalDate businessDate,
    /** Primer dia incluido en la tendencia; el ultimo es {@code businessDate}. */
    LocalDate trendFrom,
    int trendDays

) {

}
