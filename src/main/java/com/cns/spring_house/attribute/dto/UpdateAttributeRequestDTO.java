package com.cns.spring_house.attribute.dto;

import java.util.UUID;

import jakarta.validation.constraints.Pattern;

public record UpdateAttributeRequestDTO(

        UUID family,
        String name,
        @Pattern(regexp = "NUMBER|STRING|BOOLEAN",
                message = "Solo se aceptan los siguientes tipos de datos: NUMBER, STRING y BOOLEAN")
        String dataType,
        Boolean isRequired

) { }
