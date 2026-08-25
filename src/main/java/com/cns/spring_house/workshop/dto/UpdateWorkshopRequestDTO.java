package com.cns.spring_house.workshop.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

public record UpdateWorkshopRequestDTO(

        @Size(max = ColumnLimits.WORKSHOP_NAME,
                message = "El nombre del taller no puede superar los {max} caracteres")
        String name,
        String address,
        Boolean isActive

) {
}
