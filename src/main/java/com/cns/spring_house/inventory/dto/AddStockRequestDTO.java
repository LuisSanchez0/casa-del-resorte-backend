package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Entrada de inventario.
 *
 * La peticion tiene DOS formas segun la categoria del producto, y no son
 * intercambiables:
 *
 * <ul>
 * <li>NORMAL y SPECIAL: una COMPRA. Llegan {@code units}, {@code supplier},
 * {@code acquisitionType} y {@code unitCost}, los cuatro obligatorios. El
 * servicio los exige; aqui no se pueden marcar {@code @NotNull} porque la otra
 * forma no los lleva.</li>
 * <li>CONTAINER: el ARMADO de una maleta. Llega {@code containedItems} con las
 * unidades fisicas que la componen. No es una compra —esos componentes ya eran
 * del taller—, asi que no lleva proveedor ni costo.</li>
 * </ul>
 *
 * Lo que el cliente NO envia, porque lo decide el servidor:
 *
 * <pre>
 * el taller                 sale del token
 * acquiredAt                reloj de negocio del servidor
 * quantityRemaining         nace igual a lo recibido
 * el total de la compra     se deriva de cantidad x costo unitario
 * el empleado               sale de la cuenta autenticada
 * </pre>
 *
 * El total no viaja a proposito: aceptarlo permitiria enviar un importe que no
 * cuadra con sus dos factores y no habria forma de saber cual de los tres es el
 * bueno. La interfaz lo muestra calculado, el servidor lo recalcula igual.
 */
public record AddStockRequestDTO(

        @Positive(
                message = "Las unidades a agregar deben ser mayores que cero")
        @Max(value = ColumnLimits.SMALLINT_MAX,
                message = "El valor maximo permitido es {value}")
        Integer units,

        /** Proveedor del taller autenticado. Obligatorio en una compra. */
        UUID supplier,

        /** Nacional o importacion. Obligatorio en una compra. */
        AcquisitionType acquisitionType,

        /**
         * Costo de UNA unidad. Se admite cero —una promocion, una reposicion de
         * garantia— pero nunca negativo. La precision es la de la columna:
         * `NUMERIC(10,2)`.
         */
        @PositiveOrZero(
                message = "El costo unitario no puede ser negativo")
        @Digits(
                integer = ColumnLimits.MONEY_INTEGER_DIGITS,
                fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
                message = "El costo unitario admite hasta {integer} enteros y {fraction} decimales")
        BigDecimal unitCost,

        @Size(
                min = 1,
                message = "Debe especificarse al menos un producto contenido")
        List<UUID> containedItems

) {
}
