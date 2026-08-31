package com.cns.spring_house.cashclosure.entity;

/**
 * En que papel aparece una factura cambiaria dentro de un cierre.
 *
 * Son dos conjuntos DISTINTOS y no se pueden fundir en uno:
 *
 * <ul>
 * <li>{@code ISSUED}: se emitio en el periodo. Puede estar cobrada o no.</li>
 * <li>{@code COLLECTED}: se cobro en el periodo. Pudo emitirse hace un mes.</li>
 * </ul>
 *
 * Una cambiaria emitida y cobrada el mismo dia aparece en los DOS apartados,
 * cada uno con su scope. Eso no la cuenta dos veces: el total cobrado no se suma
 * de esta lista sino del desglose por forma de pago, donde figura una sola vez.
 */
public enum ExchangeInvoiceScope {

    ISSUED,
    COLLECTED;

}
