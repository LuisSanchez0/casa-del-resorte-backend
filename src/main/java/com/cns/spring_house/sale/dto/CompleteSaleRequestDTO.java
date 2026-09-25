package com.cns.spring_house.sale.dto;

import java.util.UUID;

import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * `paymentMethod` no se puede exigir aqui con @NotNull porque depende del tipo de
 * documento: obligatorio en una factura normal y prohibido en una cambiaria, que
 * se emite sin cobrar. Esa regla cruzada vive en el servicio.
 *
 * `taxMode`, `customer` y `customerName` fijan la identidad fiscal con la que se
 * emite, con las mismas reglas que el alta. Es aqui donde una venta guardada sin
 * definir elige NIT o CF, en la misma transaccion que la emite. Ausente,
 * `taxMode` conserva la identidad que ya tenia la venta: una venta con NIT se
 * completa como siempre y una sin definir se rechaza, jamas se convierte en CF.
 */
public record CompleteSaleRequestDTO(

    @NotNull(message = "El tipo de documento es obligatorio")
    InvoiceType invoiceType,
    PaymentMethod paymentMethod,
    TaxMode taxMode,
    UUID customer,
    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String customerName

) {

    public CompleteSaleRequestDTO {
        customerName = SaleNames.normalize(customerName);
    }

    /** Contrato anterior: emite con la identidad fiscal que ya tiene la venta. */
    public CompleteSaleRequestDTO(InvoiceType invoiceType, PaymentMethod paymentMethod) {
        this(invoiceType, paymentMethod, null, null, null);
    }

}
