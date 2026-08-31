package com.cns.spring_house.model.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * `year` es un año suelto por el que se pregunta, no un extremo del intervalo:
 * devuelve los modelos que lo CUBREN, así que un 2023 encuentra tanto al
 * modelo 2023 como al 2020-2025.
 */
public record SearchModelRequestDTO(

        UUID brand,
        String name,
        @Schema(description = "Año a cubrir: devuelve los modelos cuyo intervalo lo contiene",
                example = "2023")
        Integer year

) { }
