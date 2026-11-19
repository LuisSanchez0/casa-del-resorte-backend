package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * El vendedor corrige el precio de UNA linea de una venta abierta, sin
 * quitarla ni volver a agregarla: una linea directa (pieza normal de stock,
 * maleta, servicio ya ejecutado), una solicitud todavia sin enviar, o una
 * solicitud que el taller devolvio a revision de precio.
 *
 * `expectedPrice` es OBLIGATORIO: es la precondicion que impide pisar un
 * cambio que el vendedor no vio (409). Sin el, la edicion se rechaza; no hay
 * una escritura "a ciegas".
 */
public record UpdateRequestPriceDTO(

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.00", message = "El precio no puede ser negativo")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El precio admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal price,
    /**
     * El precio de la linea que el vendedor tenia a la vista al editarla. Si la
     * linea ya tiene otro (otra pestana la edito), la edicion se rechaza con
     * 409. Se compara por valor numerico (500.0 == 500.00).
     */
    @NotNull(message = "Indique el precio que tenia a la vista: sin el no se puede saber si la linea cambio")
    @DecimalMin(value = "0.00", message = "El precio esperado no puede ser negativo")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El precio esperado admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal expectedPrice,
    /** "Sin cargo" elegido expresamente: la unica forma de dejar la linea en Q0.00. */
    Boolean noCharge

) {

    /** Sin "sin cargo": un cero se rechaza. */
    public UpdateRequestPriceDTO(BigDecimal price, BigDecimal expectedPrice) {
        this(price, expectedPrice, null);
    }

    public boolean chargesNothing() {
        return Boolean.TRUE.equals(noCharge);
    }

}
