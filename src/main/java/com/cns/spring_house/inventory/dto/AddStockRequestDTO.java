package com.cns.spring_house.inventory.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;

public record AddStockRequestDTO(

        @Min(value = 0, message = "Las unidades a agregar deben ser mayor o igual a {value}")
        Integer units,
        List<UUID> children

) {
    
}
