package com.cns.spring_house.workshop.dto;

public record UpdateWorkshopRequestDTO(

        String name,
        String address,
        Boolean isActive

) {
}
