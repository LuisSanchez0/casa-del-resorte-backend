package com.cns.spring_house.dto.workshop;

public record UpdateWorkshopRequestDTO(

        String name,
        String address,
        Boolean isActive

) {
}
