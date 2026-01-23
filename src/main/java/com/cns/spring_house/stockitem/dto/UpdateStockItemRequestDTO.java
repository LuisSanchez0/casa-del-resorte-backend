package com.cns.spring_house.stockitem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.Min;

public record UpdateStockItemRequestDTO(

        UUID workshop,
        UUID product,
        @Min(value = 0, message = "El precio debe ser mayor a 0")
        BigDecimal price,
        @Min(value = 0, message = "El ultimo costo de compra debe ser mayor a 0")
        BigDecimal lastCost,
        @Min(value = 0, message = "La cantidad debe ser mayor a 0")
        Integer quantity,
        @Min(value = 0, message = "El umbral minimo debe ser mayor a 0")
        Integer minThreshold

) { }
