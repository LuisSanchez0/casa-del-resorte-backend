package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo COBRADO: dinero que de verdad entro, contado por {@code paidAt}.
 *
 * No coincide con lo facturado y no tiene por que. Una cambiaria emitida hoy no
 * aporta nada aqui; una emitida el mes pasado y cobrada hoy aporta todo su
 * importe sin sumar un quetzal a la facturacion del dia.
 *
 * {@code totalCollected} es la suma de {@code methods} y es EL total del cierre.
 * No se deduce de lo emitido: emitir no es cobrar, y calcularlo desde la
 * facturacion contaria como ingreso el credito que nadie ha pagado.
 */
public record CashClosureCollectionsDTO(

    /** Una fila por forma de pago, incluidas las que quedaron en cero. */
    List<ClosurePaymentMethodDTO> methods,
    /** Suma exacta de {@code methods}. */
    BigDecimal totalCollected

) { }
