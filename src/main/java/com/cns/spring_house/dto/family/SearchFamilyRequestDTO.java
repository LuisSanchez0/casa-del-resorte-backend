package com.cns.spring_house.dto.family;

public record SearchFamilyRequestDTO(

        String q,
        Boolean requiresModel

) {
}
