package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.sale.entity.SaleStatus;

public record SearchSaleRequestDTO(

    String customer,
    BigDecimal minTotal,
    BigDecimal maxTotal,
    LocalDateTime minDate,
    LocalDateTime maxDate,
    UUID workshop,
    UUID employee,
    SaleStatus status

) {
    
}
