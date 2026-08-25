package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateInventoryRequestDTO(

        @NotNull(message = "El producto es obligatorio")
        UUID product,
        @NotNull(message = "El precio es obligatorio")
        @Min(value = 0, message = "El precio debe ser mayor o igual a {value}")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal price,
        @NotNull(message = "El ultimo costo de compra es obligatorio")
        @Min(value = 0, message = "El ultimo costo de compra debe ser mayor o igual a {value}")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal cost,
        @NotNull(message = "El umbral minimo es obligatorio")
        @Min(value = 0, message = "El umbral minimo debe ser mayor o igual a {value}")
        @Max(value = ColumnLimits.SMALLINT_MAX,
                message = "El valor maximo permitido es {value}")
        Integer minStock

) {
}
