package com.cns.spring_house.family.dto;

import com.cns.spring_house.family.entity.FamilyCategory;

public record SearchFamilyRequestDTO(

        String q,
        Boolean requiresModel,
        FamilyCategory category

) {
}
