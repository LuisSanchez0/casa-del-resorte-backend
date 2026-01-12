package com.cns.spring_house.dto.product;

import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.dto.model.ModelResponseDTO;

public record ProductResponseDTO(

        UUID id,
        String sku,
        String name,
        ModelResponseDTO model,
        Map<String, Object> attributes

) {
}
