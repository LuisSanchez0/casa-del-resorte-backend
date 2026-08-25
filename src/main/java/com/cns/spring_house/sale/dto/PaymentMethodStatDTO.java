package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;

import com.cns.spring_house.sale.entity.PaymentMethod;

/**
 * Cobros de hoy de una forma de pago concreta. Solo incluye ventas realmente
 * cobradas hoy (`paymentStatus = PAID`, `paidAt` de hoy); una factura
 * cambiaria pendiente nunca aparece aqui.
 */
public record PaymentMethodStatDTO(

    PaymentMethod method,
    long saleCount,
    BigDecimal amount

) {

}
