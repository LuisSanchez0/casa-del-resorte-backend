package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un punto de la serie de RENTABILIDAD DE VENTAS.
 *
 * {@code costOfGoodsSold} y {@code grossProfit} suman SOLO las lineas con costo
 * historico conocido. {@code uncoveredLines} y {@code uncoveredRevenue}
 * describen lo que quedo fuera, para que la interfaz pueda decir de que tamaño
 * es el hueco en vez de disimularlo sumando ceros.
 */
public record SalesCostProfitPointDTO(

        LocalDate bucket,
        BigDecimal coveredRevenue,
        BigDecimal costOfGoodsSold,
        BigDecimal grossProfit,
        long coveredLines,
        long uncoveredLines,
        BigDecimal uncoveredRevenue

) { }
