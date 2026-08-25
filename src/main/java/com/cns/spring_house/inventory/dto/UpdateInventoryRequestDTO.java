package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;

public record UpdateInventoryRequestDTO(

        @Min(value = 0, message = "El precio debe ser mayor o igual a {value}")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal price,
        @Min(value = 0, message = "El ultimo costo de compra debe ser mayor o igual a {value}")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal cost,
        @Min(value = 0, message = "El umbral minimo debe ser mayor o igual a {value}")
        @Max(value = ColumnLimits.SMALLINT_MAX,
                message = "El valor maximo permitido es {value}")
        Integer minStock

) {
}
