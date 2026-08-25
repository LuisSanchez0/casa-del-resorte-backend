package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

/**
 * Lo COBRADO: dinero que entro, contado por {@code paidAt} y desglosado por
 * forma de pago.
 *
 * No coincide con lo facturado y no tiene por que: una cambiaria emitida hoy no
 * aporta nada aqui, y una emitida el mes pasado y cobrada hoy aporta todo su
 * importe sin sumar un quetzal a la facturacion del dia.
 */
public record CashClosureCollectionsDTO(

    long cashCount,
    BigDecimal cashAmount,
    long cardCount,
    BigDecimal cardAmount,
    /** Transferencia o deposito: {@code PaymentMethod.TRANSFER} cubre ambos. */
    long transferCount,
    BigDecimal transferAmount,
    /** {@code cashAmount + cardAmount + transferAmount}. */
    BigDecimal totalCollected

) {

}
