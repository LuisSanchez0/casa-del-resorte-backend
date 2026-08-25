package com.cns.spring_house.product.dto;

import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProductRequestDTO(

        @NotBlank(message = "El sku es obligatorio")
        @Size(max = ColumnLimits.PRODUCT_SKU,
                message = "El sku no puede superar los {max} caracteres")
        String sku,
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = ColumnLimits.PRODUCT_NAME,
                message = "El nombre no puede superar los {max} caracteres")
        String name,
        @NotNull(message = "La familia es obligatoria") UUID family,
        @NotNull(message = "Los atributos son obligatorios") Map<String, Object> attributes,
        UUID model

) {
}
