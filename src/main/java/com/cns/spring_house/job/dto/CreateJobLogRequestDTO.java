package com.cns.spring_house.job.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateJobLogRequestDTO(

    @NotNull(message = "El servicio es obligatorio")
    UUID job

) {
    
}
