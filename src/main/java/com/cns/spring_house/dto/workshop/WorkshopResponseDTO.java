package com.cns.spring_house.dto.workshop;

import java.util.UUID;

public record WorkshopResponseDTO(

        UUID id,
        String name,
        String address,
        Boolean isActive

) {
}