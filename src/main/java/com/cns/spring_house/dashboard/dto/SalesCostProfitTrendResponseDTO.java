package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta de la serie de RENTABILIDAD DE VENTAS: cuanto costo la mercaderia
 * despachada y cuanto quedo despues de restarla.
 *
 * <pre>
 * gananciaBruta = subTotal historico de la linea - costTotal historico
 * </pre>
 *
 * Las dos magnitudes son snapshots escritos al vender: el subtotal ya refleja el
 * ajuste manual de precio si lo hubo, y el costo salio del FIFO sobre los lotes
 * que de verdad se consumieron. Ninguna se reconstruye con el precio de catalogo
 * ni con el costo de referencia de hoy.
 *
 * Se fecha por la EMISION de la venta (`completedAt`), el mismo universo que la
 * serie de ventas generadas, de modo que las dos graficas se pueden leer juntas.
 * No es caja: una cambiaria emitida y no cobrada ya entrego mercaderia y ya
 * genero margen.
 *
 * {@code coverage} resume si el periodo se puede leer al pie de la letra:
 * COMPLETE cuando todas las lineas de producto tenian costo, PARTIAL cuando
 * alguna no, EMPTY cuando no hubo ventas. La interfaz lo rotula en vez de
 * presentar una ganancia parcial como si fuera total.
 */
public record SalesCostProfitTrendResponseDTO(

        SalesGranularity granularity,
        LocalDate from,
        LocalDate to,
        BigDecimal totalCoveredRevenue,
        BigDecimal totalCostOfGoodsSold,
        BigDecimal totalGrossProfit,
        long coveredLines,
        long uncoveredLines,
        BigDecimal uncoveredRevenue,
        CostCoverage coverage,
        List<SalesCostProfitPointDTO> points

) { }
