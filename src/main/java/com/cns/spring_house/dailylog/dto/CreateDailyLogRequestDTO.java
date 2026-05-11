package com.cns.spring_house.dailylog.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDailyLogRequestDTO(

    @NotBlank(message = "La descripcion es obligatoria")
    String description

) {
    
}
