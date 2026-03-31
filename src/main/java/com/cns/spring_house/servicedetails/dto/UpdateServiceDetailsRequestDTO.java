package com.cns.spring_house.servicedetails.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateServiceDetailsRequestDTO(

        @NotNull(message = "El precio es obligatorio")
        @Min(value = 0, message = "El precio debe ser mayor a 0")
        BigDecimal price

) {
    
}
