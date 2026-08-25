package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo UNICO que el cliente aporta al cierre: el fondo con el que arranco la caja,
 * el efectivo que se conto al final y una nota opcional.
 *
 * Ni un solo total viaja en esta peticion. `invoiceTotal`, `cashPayments`,
 * `totalCollected`, `expectedCash`, `difference` y `pendingExchangeAmount` los
 * calcula el servidor contra la base de datos; aceptarlos del cliente
 * permitiria cerrar una caja con las cifras que uno quisiera.
 *
 * El mismo contrato sirve para {@code POST /preview}, {@code POST /preview/pdf}
 * y {@code POST /v1/cash-closures}: la previsualizacion tiene que recibir
 * exactamente lo mismo que el cierre para poder mostrar de antemano lo que se
 * va a confirmar.
 */
public record CreateCashClosureRequestDTO(

    @NotNull(message = "El fondo inicial es obligatorio")
    @DecimalMin(value = "0.00", message = "El fondo inicial no puede ser negativo")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El fondo inicial admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal openingCash,

    @NotNull(message = "El efectivo contado es obligatorio")
    @DecimalMin(value = "0.00", message = "El efectivo contado no puede ser negativo")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El efectivo contado admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal countedCash,

    @Size(max = ColumnLimits.CASH_CLOSURE_NOTES,
            message = "Las observaciones no pueden superar los {max} caracteres")
    String notes

) {

    /**
     * Una nota en blanco se guarda como null y no como cadena vacia: es un dato
     * opcional, y el reporte solo imprime el apartado de observaciones cuando
     * hay algo que observar.
     */
    public CreateCashClosureRequestDTO {
        notes = notes == null || notes.isBlank() ? null : notes.trim();
    }

}
