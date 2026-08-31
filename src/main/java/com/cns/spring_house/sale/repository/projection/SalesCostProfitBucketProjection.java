package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;

/**
 * Un punto de la serie de RENTABILIDAD DE VENTAS, ya agrupado por la unidad de
 * tiempo que pidio el administrador.
 *
 * Esto NO es gasto en compras. Mide el rendimiento de lo VENDIDO —cuanto costo
 * la mercaderia que salio y cuanto quedo— y se fecha por la venta, no por la
 * compra. La mercaderia puede haberse comprado meses antes; ese desembolso vive
 * en la otra serie y las dos no se restan entre si.
 *
 * {@code bucketDate} viaja como TEXTO {@code YYYY-MM-DD} por el mismo motivo que
 * el resto de proyecciones nativas del proyecto.
 *
 * La COBERTURA viaja en la propia fila y no se deduce despues:
 * {@code coveredLines} son las lineas con costo historico conocido y
 * {@code uncoveredLines} las que no lo tienen (un contenedor armado, la salida
 * de una transformacion). {@code costOfGoodsSold} y {@code grossProfit} suman
 * SOLO sobre las cubiertas, de modo que una linea sin costo nunca se cuela como
 * si hubiera costado cero; {@code uncoveredRevenue} dice cuanta venta queda
 * fuera de esa cuenta, para que la interfaz pueda decir de que tamaño es el
 * hueco en lugar de ocultarlo.
 */
public interface SalesCostProfitBucketProjection {

    String getBucketDate();

    BigDecimal getCoveredRevenue();

    BigDecimal getCostOfGoodsSold();

    BigDecimal getGrossProfit();

    long getCoveredLines();

    long getUncoveredLines();

    BigDecimal getUncoveredRevenue();

}
