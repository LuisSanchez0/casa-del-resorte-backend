package com.cns.spring_house.product.dto;

import java.util.UUID;

import com.cns.spring_house.family.entity.FamilyCategory;

public record ProductFamilyResponseDTO(

        UUID id,
        String code,
        String name,
        Boolean requiresModel,
        FamilyCategory category

) {
}