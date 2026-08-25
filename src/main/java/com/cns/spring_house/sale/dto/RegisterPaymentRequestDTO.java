package com.cns.spring_house.sale.dto;

import com.cns.spring_house.sale.entity.PaymentMethod;

import jakarta.validation.constraints.NotNull;

/** Cobro posterior de una factura cambiaria ya emitida. */
public record RegisterPaymentRequestDTO(

    @NotNull(message = "El metodo de pago es obligatorio")
    PaymentMethod paymentMethod

) {

}
