package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;

import com.cns.spring_house.sale.entity.PaymentMethod;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * La devolucion de dinero que acompana a una reversion, cuando la hubo.
 *
 * Su AUSENCIA es la respuesta "no se devolvio dinero". Por eso es un objeto
 * anidado opcional y no un par de campos sueltos en la peticion: un
 * `amount = 0` con `paymentMethod = null` seria una devolucion falsa que habria
 * que aprender a distinguir de una real en cada sitio que la lea.
 *
 * Solo dos campos, y los dos son del cliente porque solo el sabe cuanto y como
 * se devolvio. Todo lo demas —cuando, quien, en que taller— lo resuelve el
 * servidor: son los datos que deciden en que cierre cae el dinero y quien
 * responde por el, y ninguno de los dos puede depender de lo que envie una
 * pantalla.
 */
public record RefundRequestDTO(

    @NotNull(message = "El monto devuelto es obligatorio")
    @Positive(message = "El monto devuelto debe ser mayor que cero")
    @Digits(integer = 8, fraction = 2,
            message = "El monto devuelto admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal amount,

    @NotNull(message = "La forma de devolucion es obligatoria")
    PaymentMethod paymentMethod

) { }
