package com.cns.spring_house.model.dto;

import java.util.UUID;

import com.cns.spring_house.brand.dto.BrandResponseDTO;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * El intervalo viaja normalizado. Quien lo muestre decide cómo escribirlo:
 * "2020" cuando los dos extremos coinciden y "2020-2025" cuando no.
 *
 * La MARCA viaja dentro del modelo porque es donde vive en el dominio: la
 * relación es `Product -> Model -> Brand` y `Model.brand` es la única fuente.
 * Antes solo existía como FILTRO de búsqueda (`SearchModelRequestDTO.brand`,
 * `SearchProductRequestDTO.brand`), de modo que se podía preguntar por una marca
 * pero nunca leer la del modelo que devolvía la respuesta. Quien recibía un
 * producto tenía su modelo y no podía saber de qué marca era sin una segunda
 * consulta, y por eso la caja no lograba nombrar la línea con su marca.
 *
 * Es aditivo: quien no la mire sigue leyendo el mismo JSON de siempre, y un
 * modelo sin marca la manda en null como cualquier otro campo opcional.
 */
@Schema(description = "Modelo con su intervalo de años normalizado y la marca a la que pertenece")
public record ModelResponseDTO(

        UUID id,
        String name,
        @Schema(description = "Primer año del intervalo", example = "2020") Integer yearFrom,
        @Schema(description = "Último año del intervalo; igual a yearFrom en un modelo de un solo año",
                example = "2025")
        Integer yearTo,
        @Schema(description = "Marca del modelo; null si el modelo no la tiene asignada")
        BrandResponseDTO brand

) { }
