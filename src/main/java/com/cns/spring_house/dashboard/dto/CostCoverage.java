package com.cns.spring_house.dashboard.dto;

/**
 * Hasta que punto la ganancia de un periodo se puede leer al pie de la letra.
 *
 * Existe para no tener que elegir entre dos mentiras: ocultar que faltan costos,
 * o rellenarlos con cero y presentar como ganancia el precio integro de una
 * mercaderia que si costo algo.
 *
 * <ul>
 * <li>{@code EMPTY}: no hubo lineas de producto en el periodo. No hay nada que
 * cubrir, que es distinto de no haber podido cubrirlo.</li>
 * <li>{@code COMPLETE}: todas las lineas traian costo historico. Las cifras son
 * exactas.</li>
 * <li>{@code PARTIAL}: alguna linea no tiene costo de adquisicion —un contenedor
 * armado, la salida de una transformacion—, asi que el costo y la ganancia
 * describen solo la parte cubierta. La venta que queda fuera viaja aparte para
 * que se vea de que tamaño es el hueco.</li>
 * </ul>
 *
 * Tras el reinicio del inventario, toda mercaderia COMPRADA nace con lote, de
 * modo que {@code PARTIAL} ya no puede venir de datos heredados: solo de las
 * categorias que este modelo deja fuera a proposito y de forma declarada.
 */
public enum CostCoverage {

    EMPTY,
    COMPLETE,
    PARTIAL;

}
