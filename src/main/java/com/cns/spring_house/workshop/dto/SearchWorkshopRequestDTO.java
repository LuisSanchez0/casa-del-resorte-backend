package com.cns.spring_house.workshop.dto;

public record SearchWorkshopRequestDTO(

        String name,
        String address,
        Boolean active

) {
}
