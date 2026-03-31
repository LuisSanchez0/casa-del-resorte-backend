package com.cns.spring_house.transformation.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateTransformationRequestDTO(

        @NotNull(message = "El item de entrada es obligatorio")
        UUID inputItem,
        Double newLength,
        UUID outputProduct

) {
    
}
