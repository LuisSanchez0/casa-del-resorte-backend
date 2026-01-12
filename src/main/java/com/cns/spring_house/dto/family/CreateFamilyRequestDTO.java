package com.cns.spring_house.dto.family;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateFamilyRequestDTO(

        @NotBlank(message = "El codigo de familia es obligatorio") String code,
        @NotBlank(message = "El nombre de la familia es obligatorio") String name,
        @NotNull(message = "Es obligatorio especificar si necesita un modelo") Boolean requiresModel

) {
}
