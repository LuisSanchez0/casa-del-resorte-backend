package com.cns.spring_house.family.dto;

import java.util.List;
import java.util.UUID;

public record FamilyResponseDTO(

        UUID id,
        String code,
        String name,
        Boolean requiresModel,
        String category,
        List<AttributeResponseDTO> attributes

) {
}