package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;

import com.cns.spring_house.sale.entity.PaymentMethod;

/** Lo devuelto por UNA forma de devolucion. */
public record ClosureRefundMethodDTO(

    PaymentMethod method,
    long count,
    BigDecimal amount

) { }
