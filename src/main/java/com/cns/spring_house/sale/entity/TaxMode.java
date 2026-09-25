package com.cns.spring_house.sale.entity;

/**
 * Identidad fiscal con la que se factura la venta, independiente de
 * {@link SaleStatus}: una venta puede estar PENDING con NIT ya elegido, o
 * PENDING sin haberlo decidido todavia.
 *
 * {@code NIT} factura a un {@code Customer} registrado: el nombre y el NIT del
 * documento son los de ese cliente.
 *
 * {@code CF} es Consumidor Final, elegido EXPRESAMENTE por el vendedor al
 * emitir. Es una identidad FINAL: solo existe en una venta cerrada, porque se
 * fija en la misma transaccion que la emite. No lleva cliente ni NIT; el nombre
 * es opcional y, si falta, el documento dice "Consumidor Final".
 *
 * {@code UNRESOLVED} es toda venta abierta sin NIT: el carrito en curso —aunque
 * el vendedor piense emitirlo a Consumidor Final— o la venta guardada para
 * despues con su nombre de referencia. NO es CF ni un NIT a medias: la forma de
 * facturacion sigue sin elegirse, y por eso no puede completarse hasta que el
 * vendedor elija NIT o CF.
 */
public enum TaxMode {

    NIT,
    CF,
    UNRESOLVED;

}
