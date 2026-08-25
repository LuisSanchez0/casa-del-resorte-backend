package com.cns.spring_house.family.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateFamilyRequestDTO(

        @NotBlank(message = "El codigo de familia es obligatorio")
        @Size(max = ColumnLimits.FAMILY_CODE,
                message = "El codigo de la familia no puede superar los {max} caracteres")
        String code,
        @NotBlank(message = "El nombre de la familia es obligatorio")
        @Size(max = ColumnLimits.FAMILY_NAME,
                message = "El nombre de la familia no puede superar los {max} caracteres")
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
