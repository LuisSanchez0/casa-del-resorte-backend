package com.cns.spring_house.dailylog.dto;

import java.math.BigDecimal;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Linea de servicio de un Daily Log. Igual que la de producto, sin id: el
 * nombre se comprueba contra {@code JobRepository} —nunca contra
 * {@code ProductRepository}, aunque un Job tenga producto asociado— y despues
 * se guarda como texto. Ver {@code DailyLogService}.
 */
public record DailyLogServiceRequestDTO(

        @NotBlank(message = "El nombre del servicio es obligatorio")
        @Size(max = ColumnLimits.PRODUCT_NAME,
                message = "El nombre del servicio no puede superar los {max} caracteres")
        String name,
        @NotNull(message = "El precio unitario es obligatorio")
        @DecimalMin(value = "0", message = "El precio unitario no puede ser negativo")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal unitPrice,
        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 1, message = "La cantidad debe ser al menos {value}")
        Integer quantity,
        @NotNull(message = "El subtotal es obligatorio")
        @DecimalMin(value = "0", message = "El subtotal no puede ser negativo")
        @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El importe admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal subtotal

) {
}
