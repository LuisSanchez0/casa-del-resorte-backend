package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * El vendedor revisa el precio de una solicitud: antes de enviarla, o cuando
 * el taller la devolvio a revision porque la pieza de origen cuesta mas.
 */
public record UpdateRequestPriceDTO(

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.01", message = "El precio debe ser mayor que cero")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El precio admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal price

) {

}
