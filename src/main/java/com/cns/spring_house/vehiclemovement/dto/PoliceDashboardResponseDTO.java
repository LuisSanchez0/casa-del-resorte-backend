package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta completa del dashboard policial en UNA sola peticion: cambiar de
 * ambito en la interfaz no debe volver a consultar el servidor.
 *
 * Todo viene acotado al taller del token. El ambito personal se resuelve tambien
 * en el servidor a partir del empleado autenticado: el cliente no envia (ni
 * puede enviar) un identificador de empleado o de taller.
 *
 * {@code personalScopeAvailable} es false cuando la cuenta autenticada no tiene
 * empleado asociado; en ese caso {@code mine} llega en ceros y la interfaz
 * desactiva el ambito personal en lugar de mostrar cifras vacias como si fueran
 * reales.
 *
 * {@code unattributedHistoricalEntries} cuenta las entradas del taller sin actor
 * (anteriores a V23). Se expone para poder explicar en la interfaz por que la
 * suma personal de todos los empleados no llega al total historico.
 */
public record PoliceDashboardResponseDTO(

        PoliceDashboardStatsDTO general,
        PoliceDashboardStatsDTO mine,
        List<CurrentVehicleDTO> currentVehicles,
        boolean personalScopeAvailable,
        long unattributedHistoricalEntries,
        /** Fecha de negocio usada para el KPI de hoy, resuelta en el servidor. */
        LocalDate businessDate,
        /** Primer dia incluido en la tendencia; ultimo dia es businessDate. */
        LocalDate trendFrom,
        int trendDays,
        int topPlatesLimit

) {

}
