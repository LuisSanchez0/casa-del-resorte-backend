package com.cns.spring_house.product.dto;

import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.model.dto.ModelResponseDTO;

public record ProductResponseDTO(

        UUID id,
        String sku,
        String name,
        ModelResponseDTO model,
        Map<String, Object> attributes

) {
}
