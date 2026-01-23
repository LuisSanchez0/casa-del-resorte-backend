package com.cns.spring_house.product.dto;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProductRequestDTO(

        @NotBlank(message = "El sku es obligatorio") String sku,
        @NotBlank(message = "El nombre es obligatorio") String name,
        @NotNull(message = "La familia es obligatoria") UUID family,
        @NotNull(message = "Los atributos son obligatorios") Map<String, Object> attributes,
        UUID model

) {
}
