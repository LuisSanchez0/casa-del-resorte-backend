package com.cns.spring_house.sale.entity;

/**
 * Estado del COBRO, deliberadamente separado de {@link SaleStatus}, que describe
 * el ciclo de vida comercial de la venta.
 *
 * Sin esta separacion una factura cambiaria emitida pero no pagada tendria que
 * quedarse en {@code SaleStatus.PENDING}, y ese estado es el que habilita agregar
 * y quitar detalles: una factura ya emitida volveria a ser editable como carrito.
 */
public enum PaymentStatus {

    PENDING,
    PAID;

}
