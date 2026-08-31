package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

import com.cns.spring_house.sale.entity.PaymentMethod;

/**
 * Lo cobrado en el periodo por UNA forma de pago.
 *
 * Se devuelve una fila por cada valor del enum, incluso con cero: un cierre que
 * omitiera "Tarjeta: Q0.00" obligaria a la pantalla a saber que metodos existen
 * para poder pintar el que falta.
 */
public record ClosurePaymentMethodDTO(

    PaymentMethod method,
    long count,
    BigDecimal amount

) { }
