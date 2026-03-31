package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateInventoryRequestDTO(

        @NotNull(message = "El producto es obligatorio")
        UUID product,
        @NotNull(message = "El precio es obligatorio")
        @Min(value = 0, message = "El precio debe ser mayor o igual a {value}")
        BigDecimal price,
        @NotNull(message = "El ultimo costo de compra es obligatorio")
        @Min(value = 0, message = "El ultimo costo de compra debe ser mayor o igual a {value}")
        BigDecimal cost,
        @NotNull(message = "El umbral minimo es obligatorio")
        @Min(value = 0, message = "El umbral minimo debe ser mayor o igual a {value}")
        Integer minStock

) {
}
