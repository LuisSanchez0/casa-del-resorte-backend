package com.cns.spring_house.family.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAttributeRequestDTO(

        @NotBlank(message = "La nombre del atributo es obligatoria")
        @Size(max = ColumnLimits.ATTRIBUTE_NAME,
                message = "El nombre del atributo no puede superar los {max} caracteres")
        String name,
        @NotNull(message = "El tipo de dato del atributo es obligatorio")
        @Pattern(regexp = "NUMBER|STRING|BOOLEAN",
                message = "Solo se aceptan los siguientes tipos de datos: NUMBER, STRING y BOOLEAN")
        String dataType,
        @NotNull(message = "Es necesario especificar si el atributo es obligatorio")
        Boolean isRequired

) { }
