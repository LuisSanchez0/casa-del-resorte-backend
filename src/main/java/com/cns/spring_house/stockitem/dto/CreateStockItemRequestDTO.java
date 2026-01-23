package com.cns.spring_house.stockitem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateStockItemRequestDTO(

        @NotNull(message = "El taller es obligatorio")
        UUID workshop,
        @NotNull(message = "El producto es obligatorio")
        UUID product,
        @NotNull(message = "El precio es obligatorio")
        @Min(value = 0, message = "El precio debe ser mayor a 0")
        BigDecimal price,
        @NotNull(message = "El ultimo costo de compra es obligatorio")
        @Min(value = 0, message = "El ultimo costo de compra debe ser mayor a 0")
        BigDecimal lastCost,
        @NotNull(message = "La cantidad es obligatoria")
        @Min(value = 0, message = "La cantidad debe ser mayor a 0")
        Integer quantity,
        @NotNull(message = "El umbral minimo es obligatorio")
        @Min(value = 0, message = "El umbral minimo debe ser mayor a 0")
        Integer minThreshold

) { }
