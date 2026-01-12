package com.cns.spring_house.dto.product;

import java.util.Map;
import java.util.UUID;

public record UpdateProductRequestDTO(

        String sku,
        String name,
        UUID family,
        UUID model,
        Map<String, Object> attributes

) {
}
