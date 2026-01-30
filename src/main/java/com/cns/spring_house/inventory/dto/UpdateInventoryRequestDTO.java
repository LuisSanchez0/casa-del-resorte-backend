package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Min;

public record UpdateInventoryRequestDTO(

        UUID product,
        @Min(value = 0, message = "El precio debe ser mayor o igual a {value}")
        BigDecimal price,
        @Min(value = 0, message = "El ultimo costo de compra debe ser mayor o igual a {value}")
        BigDecimal cost,
        @Min(value = 0, message = "El umbral minimo debe ser mayor o igual a {value}")
        Integer minThreshold

) {
}
