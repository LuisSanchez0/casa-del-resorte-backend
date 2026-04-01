package com.cns.spring_house.vehiclemovement.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateVehicleMovementRequestDTO(

        @NotBlank(message = "La placa es obligatoria")
        String plate,
        String notes

) {
    
}
