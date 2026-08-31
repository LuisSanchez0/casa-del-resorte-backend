package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.cns.spring_house.sale.entity.PaymentMethod;

/**
 * Una devolucion dentro del cierre.
 *
 * `documentCode` es el codigo corto legible de la venta devuelta; el UUID viaja
 * aparte solo para poder abrirla. `performedByName` es el nombre COPIADO al
 * cerrar, no una lectura del empleado de hoy: renombrar a alguien no puede
 * cambiar lo que el cierre de aquel dia dijo.
 */
public record ClosureRefundDTO(

    /**
     * Identidad del evento vivo. Viaja dentro del backend para que el snapshot
     * pueda apuntarlo, y NO se serializa: al cliente no le sirve de nada y
     * exponer identificadores que nadie va a usar solo amplia la superficie.
     */
    @JsonIgnore UUID refundId,

    UUID saleId,
    String documentCode,
    BigDecimal amount,
    LocalDateTime effectiveAt,
    PaymentMethod paymentMethod,
    String performedByName,
    String reason

) { }
