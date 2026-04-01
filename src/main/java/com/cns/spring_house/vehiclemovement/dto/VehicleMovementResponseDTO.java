package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record VehicleMovementResponseDTO(

        UUID id,
        String plate,
        WorkshopResponseDTO workshop,
        LocalDateTime entry,
        LocalDateTime exit,
        String notes

) {
    
}
