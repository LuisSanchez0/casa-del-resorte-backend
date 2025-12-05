package com.cns.spring_house.dto.product;

import java.util.Map;
import java.util.UUID;

public record ProductResponseDTO(

        UUID id,
        String sku,
        String name,
        Map<String, Object> attributes

) { }
