package com.cns.spring_house.refund.repository.projection;

import java.math.BigDecimal;

/** Lo devuelto por UNA forma de devolucion dentro del periodo. */
public interface RefundMethodStatProjection {

    String getMethod();

    long getRefundCount();

    BigDecimal getAmount();

}
