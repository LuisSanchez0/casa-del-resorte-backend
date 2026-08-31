package com.cns.spring_house.cashclosure.repository.projection;

import java.math.BigDecimal;

/** Lo cobrado en el mes por una forma de pago, sumado sobre los cierres diarios. */
public interface CashClosurePaymentMethodProjection {

    String getPaymentMethod();

    long getPaymentCount();

    BigDecimal getAmount();

}
