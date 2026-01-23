package com.cns.spring_house.attribute.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAttributeRequestDTO(

        @NotNull(message = "La familia es obligatoria")
        UUID family,
        @NotBlank(message = "La nombre del atributo es obligatoria")
        String key,
        @NotNull(message = "El tipo de dato del atributo es obligatorio")
        @Pattern(regexp = "NUMBER|STRING|BOOLEAN",
                message = "Solo se aceptan los siguientes tipos de datos: NUMBER, STRING y BOOLEAN")
        String dataType,
        @NotNull(message = "Es necesario especificar si el atributo es obligatorio")
        Boolean required

) { }
