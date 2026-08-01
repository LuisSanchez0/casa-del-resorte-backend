package com.cns.spring_house.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateFamilyRequestDTO(

        @NotBlank(message = "El codigo de familia es obligatorio")
        String code,
        @NotBlank(message = "El nombre de la familia es obligatorio")
        String name,
        @NotNull(message = "Es obligatorio especificar si necesita un modelo")
        Boolean requiresModel,
        @NotBlank(message = "La categoria es obligatoria")
        @Pattern(regexp = "JOB|SPECIAL|NORMAL|CONTAINER",
                message = "La categoria puede ser solamente JOB, SPECIAL, CONTAINER o NORMAL")
        String category

) {

    public CreateFamilyRequestDTO {
        code = code.toUpperCase();
        name = name.toUpperCase();
    }

}
