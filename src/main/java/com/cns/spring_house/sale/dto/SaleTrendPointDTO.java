package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un punto de la tendencia diaria de ventas. La serie llega densa: los dias
 * sin ventas completadas valen cero y CERO, no se omiten.
 */
public record SaleTrendPointDTO(

    LocalDate date,
    long salesCount,
    BigDecimal amount

) {

}
