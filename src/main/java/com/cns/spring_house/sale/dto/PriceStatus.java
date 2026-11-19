package com.cns.spring_house.sale.dto;

/**
 * Lo que la caja sabe del precio de una linea ANTES de agregarla.
 *
 * No es una columna ni un estado persistido: se calcula en cada consulta a
 * partir de lo que ya existe (precio de catalogo del taller, ventas
 * completadas y piezas comparables). El precio que se cobra lo confirma
 * siempre el vendedor al agregar la linea, y ese si queda en
 * `sale_detail.price`.
 *
 * <ul>
 * <li>AVAILABLE: el taller tiene precio de catalogo para la pieza exacta.</li>
 * <li>SUGGESTED: no lo tiene, pero hay una referencia confiable: el ultimo
 * precio cobrado por la misma pieza, o el de una pieza equivalente (mismas
 * caracteristicas). Es orientativa: nunca se aplica sola.</li>
 * <li>REQUIRED: no hay referencia confiable; el vendedor define el precio a
 * mano. Puede traer piezas SIMILARES como contexto, que no cambian este
 * estado.</li>
 * </ul>
 */
public enum PriceStatus {
    AVAILABLE,
    SUGGESTED,
    REQUIRED
}
