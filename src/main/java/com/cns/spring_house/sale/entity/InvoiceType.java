package com.cns.spring_house.sale.entity;

/**
 * Tipo de documento emitido al finalizar la venta.
 *
 * {@code INVOICE} es la factura normal, cobrada en el acto.
 * {@code EXCHANGE_INVOICE} es la factura cambiaria: se emite y el cobro queda
 * pendiente, sin que la venta siga siendo editable.
 */
public enum InvoiceType {

    INVOICE,
    EXCHANGE_INVOICE;

}
