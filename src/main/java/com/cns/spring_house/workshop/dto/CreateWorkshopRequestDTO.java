package com.cns.spring_house.workshop.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkshopRequestDTO(

        @NotBlank(message = "El nombre del taller es obligatorio")
        @Size(max = ColumnLimits.WORKSHOP_NAME,
                message = "El nombre del taller no puede superar los {max} caracteres")
        String name,
        @NotBlank(message = "La direccion del taller es obligatoria") String address

) {
}
