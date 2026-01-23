package com.cns.spring_house.family.dto;

public record SearchFamilyRequestDTO(

        String q,
        Boolean requiresModel

) {
}
