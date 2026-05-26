package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateSaleDetailRequestDTO(

    @NotNull(message = "El id es obligatorio")
    UUID id,
    @Min(value = 1, message = "La cantidad debe ser mayor o igual a 1")
    Integer quantity,
    @Min(value = 0, message = "El precio debe ser mayor o igual a 0")
    BigDecimal price

) {

    public CreateSaleDetailRequestDTO {
        if (quantity == null) {
            quantity = 1;
        }
    }
    
}
