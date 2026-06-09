package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AddSaleDetailRequestDTO(

    @NotNull(message = "El id del producto es obligatorio")
    UUID product,
    UUID item,
    Integer quantity,
    BigDecimal price

) {
    
}
