package com.cns.spring_house.vehiclemovement.dto;

/**
 * Las cuatro cifras del encabezado del dashboard policial. Son magnitudes
 * DISTINTAS y se calculan con consultas distintas a proposito:
 *
 * <ul>
 * <li>{@code historicalEntries}: eventos de entrada (filas). Una placa que
 * entro ocho veces suma ocho.</li>
 * <li>{@code entriesToday}: esos mismos eventos acotados a la fecha de negocio
 * de hoy.</li>
 * <li>{@code currentVehiclesCount}: movimientos abiertos (exit IS NULL).</li>
 * <li>{@code uniqueHistoricalPlates}: placas normalizadas distintas. Esa misma
 * placa que entro ocho veces suma UNA.</li>
 * </ul>
 */
public record DashboardKpisDTO(

        long historicalEntries,
        long entriesToday,
        long currentVehiclesCount,
        long uniqueHistoricalPlates

) {

}
