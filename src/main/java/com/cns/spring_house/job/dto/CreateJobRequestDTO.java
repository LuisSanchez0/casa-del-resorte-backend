package com.cns.spring_house.job.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateJobRequestDTO(

        @NotNull(message = "El producto es obligatorio")
        UUID product,
        @NotNull(message = "El precio es obligatorio")
        @Min(value = 0, message = "El precio debe ser mayor a 0")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal price

) {
    
}
