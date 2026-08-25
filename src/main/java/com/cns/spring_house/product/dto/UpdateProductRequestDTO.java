package com.cns.spring_house.product.dto;

import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

public record UpdateProductRequestDTO(

        @Size(max = ColumnLimits.PRODUCT_SKU,
                message = "El sku no puede superar los {max} caracteres")
        String sku,
        @Size(max = ColumnLimits.PRODUCT_NAME,
                message = "El nombre no puede superar los {max} caracteres")
        String name,
        UUID family,
        UUID model,
        Map<String, Object> attributes

) {
}
