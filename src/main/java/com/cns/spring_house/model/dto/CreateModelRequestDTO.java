package com.cns.spring_house.model.dto;

import java.util.UUID;

import com.cns.spring_house.shared.validator.pastornextyear.PastOrNextYear;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateModelRequestDTO(

        @NotNull(message = "La marca es obligatoria") UUID brand,
        @NotBlank(message = "El nombre es obligatorio") String name,
        @NotNull(message = "El año es obligatorio") @Min(value = 1900, message = "El año no debe ser mayor a 1900") @PastOrNextYear(message = "El año debe ser menor o igual al siguiente año") Integer year

) {
}
