package com.cns.spring_house.dailylog.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDailyLogRequestDTO(

    @NotBlank(message = "La descripcion es obligatoria")
    String description

) {
    
}
