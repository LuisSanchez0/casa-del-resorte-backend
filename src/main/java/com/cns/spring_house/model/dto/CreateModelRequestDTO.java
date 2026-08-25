package com.cns.spring_house.model.dto;

import java.util.UUID;

import com.cns.spring_house.shared.validator.pastornextyear.PastOrNextYear;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateModelRequestDTO(

        @NotNull(message = "La marca es obligatoria") UUID brand,
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = ColumnLimits.MODEL_NAME,
                message = "El nombre del modelo no puede superar los {max} caracteres")
        String name,
        @NotNull(message = "El año es obligatorio") @Min(value = ColumnLimits.MODEL_YEAR_MIN, message = "El año no debe ser mayor a 1900") @PastOrNextYear(message = "El año debe ser menor o igual al siguiente año") Integer year

) {
}
