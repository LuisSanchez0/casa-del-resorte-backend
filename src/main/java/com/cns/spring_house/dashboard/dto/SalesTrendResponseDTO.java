package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta de la serie de ventas generadas.
 *
 * Viaja separada del resumen a proposito: cambiar de granularidad o de periodo
 * no debe obligar a recalcular los cinco rankings del panel, que no dependen del
 * periodo elegido.
 *
 * {@code from} y {@code to} son los limites REALES de la serie devuelta, ya
 * recortados por el servidor: pedir el mes en curso devuelve hasta hoy, no hasta
 * el dia 31. El cliente los muestra en lugar de deducir el periodo por su
 * cuenta, que es como se acaban pintando intervalos futuros vacios.
 *
 * {@code totalGeneratedAmount} es la suma de los puntos devueltos, calculada en
 * el servidor: el cliente no vuelve a sumar importes en coma flotante para
 * rotular la tarjeta.
 */
public record SalesTrendResponseDTO(

        SalesGranularity granularity,
        LocalDate from,
        LocalDate to,
        BigDecimal totalGeneratedAmount,
        long salesCount,
        List<SalesTrendPointDTO> points

) {

}
