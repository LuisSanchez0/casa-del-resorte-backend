package com.cns.spring_house.family.dto;

import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateAttributeRequestDTO(

        UUID family,
        @Size(max = ColumnLimits.ATTRIBUTE_NAME,
                message = "El nombre del atributo no puede superar los {max} caracteres")
        String name,
        @Pattern(regexp = "NUMBER|STRING|BOOLEAN",
                message = "Solo se aceptan los siguientes tipos de datos: NUMBER, STRING y BOOLEAN")
        String dataType,
        Boolean isRequired

) { }
