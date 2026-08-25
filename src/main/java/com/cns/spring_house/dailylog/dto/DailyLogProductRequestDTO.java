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
 * Linea de producto de un Daily Log. El contrato NO lleva id de producto: el
 * frontend manda SOLO el nombre —el autocompletado le ayuda a elegirlo— y el
 * backend vuelve a comprobar contra {@code ProductRepository} que ese nombre
 * existe. Ver {@code DailyLogProduct}.
 *
 * El dinero viaja como numero JSON y se recibe en {@code BigDecimal}, nunca en
 * {@code double} ni en texto, aunque la UI use un input de texto: el redondeo
 * binario no tiene sitio en un importe.
 */
public record DailyLogProductRequestDTO(

        @NotBlank(message = "El nombre del producto es obligatorio")
        @Size(max = ColumnLimits.PRODUCT_NAME,
                message = "El nombre del producto no puede superar los {max} caracteres")
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
