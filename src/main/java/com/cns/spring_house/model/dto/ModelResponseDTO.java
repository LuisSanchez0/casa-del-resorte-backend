package com.cns.spring_house.model.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * El intervalo viaja normalizado. Quien lo muestre decide cómo escribirlo:
 * "2020" cuando los dos extremos coinciden y "2020-2025" cuando no.
 */
@Schema(description = "Modelo con su intervalo de años normalizado")
public record ModelResponseDTO(

        UUID id,
        String name,
        @Schema(description = "Primer año del intervalo", example = "2020") Integer yearFrom,
        @Schema(description = "Último año del intervalo; igual a yearFrom en un modelo de un solo año",
                example = "2025")
        Integer yearTo

) { }
