package com.cns.spring_house.sale.dto;

import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;

import jakarta.validation.constraints.NotNull;

/**
 * `paymentMethod` no se puede exigir aqui con @NotNull porque depende del tipo de
 * documento: obligatorio en una factura normal y prohibido en una cambiaria, que
 * se emite sin cobrar. Esa regla cruzada vive en el servicio.
 */
public record CompleteSaleRequestDTO(

    @NotNull(message = "El tipo de documento es obligatorio")
    InvoiceType invoiceType,
    PaymentMethod paymentMethod

) {

}
