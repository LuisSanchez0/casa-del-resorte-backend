package com.cns.spring_house.model.dto;

import java.util.UUID;

import com.cns.spring_house.shared.validator.pastornextyear.PastOrNextYear;

import com.cns.spring_house.shared.constants.ColumnLimits;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * PATCH: lo que no viaja no cambia. Mover un solo extremo del intervalo es
 * legítimo —pasar de 2020-2025 a 2020-2024 solo manda `yearTo`—, así que el
 * orden de los dos extremos se comprueba en el servicio, ya fusionados con lo
 * que la fila tenía; aquí no siempre están los dos.
 */
@Schema(description = "Actualización parcial de un modelo y de su intervalo de años")
public record UpdateModelRequestDTO(

        UUID brand,
@Size(max = ColumnLimits.MODEL_NAME,
                message = "El nombre del modelo no puede superar los {max} caracteres")
        String name,
        @Schema(description = "Nuevo primer año del intervalo", example = "2020")
        @Min(value = ColumnLimits.MODEL_YEAR_MIN, message = "El año inicial no debe ser menor a 1900")
        @PastOrNextYear(message = "El año inicial debe ser menor o igual al siguiente año")
        Integer yearFrom,
        @Schema(description = "Nuevo último año del intervalo", example = "2025")
        @Min(value = ColumnLimits.MODEL_YEAR_MIN, message = "El año final no debe ser menor a 1900")
        @PastOrNextYear(message = "El año final debe ser menor o igual al siguiente año")
        Integer yearTo

) {
}
