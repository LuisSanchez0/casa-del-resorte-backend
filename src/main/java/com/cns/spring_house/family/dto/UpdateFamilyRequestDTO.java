package com.cns.spring_house.family.dto;

public record UpdateFamilyRequestDTO(

        String code,
        String name,
        Boolean requiresModel

) {
}
