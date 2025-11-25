package com.cns.spring_house.dto.workshop;

public record SearchWorkshopRequestDTO(

        String name,
        String address,
        Boolean active

) {
}
