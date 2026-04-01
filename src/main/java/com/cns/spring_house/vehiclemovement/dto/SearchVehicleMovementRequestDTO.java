package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SearchVehicleMovementRequestDTO(

        String plate,
        UUID workshop,
        LocalDateTime entry,
        LocalDateTime exit

) {
    
}
