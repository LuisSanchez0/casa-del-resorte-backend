package com.cns.spring_house.sale.entity;

/**
 * Preparacion de la venta en el taller, deliberadamente separada de
 * {@link SaleStatus}: terminar de preparar un pedido NO es facturarlo.
 *
 * <ul>
 * <li>{@code NONE}: la venta nunca envio nada al taller (un carrito directo, o
 * una comanda que todavia no se envia).</li>
 * <li>{@code IN_PROGRESS}: hay al menos una solicitud sin cumplir.</li>
 * <li>{@code READY_TO_COMPLETE}: todo lo pedido ya fue entregado, fabricado o
 * ejecutado; solo falta que el vendedor la facture.</li>
 * </ul>
 *
 * Solo la finalizacion del vendedor cambia {@link SaleStatus} a COMPLETED.
 */
public enum SaleFulfillmentStatus {

    NONE,
    IN_PROGRESS,
    READY_TO_COMPLETE;

}
