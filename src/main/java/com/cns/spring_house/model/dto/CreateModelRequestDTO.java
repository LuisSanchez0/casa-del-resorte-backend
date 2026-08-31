package com.cns.spring_house.model.dto;

import java.util.UUID;

import com.cns.spring_house.shared.validator.pastornextyear.PastOrNextYear;

import com.cns.spring_house.shared.constants.ColumnLimits;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * El alta pide el intervalo de años completo, nunca la cadena "2020-2025": un
 * modelo de un solo año se envía con los dos extremos iguales.
 */
@Schema(description = "Alta de un modelo con su intervalo de años")
public record CreateModelRequestDTO(

        @NotNull(message = "La marca es obligatoria") UUID brand,
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = ColumnLimits.MODEL_NAME,
                message = "El nombre del modelo no puede superar los {max} caracteres")
        String name,
        @Schema(description = "Primer año al que aplica el modelo. Para un solo año, igual a yearTo",
                example = "2020")
        @NotNull(message = "El año inicial es obligatorio")
        @Min(value = ColumnLimits.MODEL_YEAR_MIN, message = "El año inicial no debe ser menor a 1900")
        @PastOrNextYear(message = "El año inicial debe ser menor o igual al siguiente año")
        Integer yearFrom,
        @Schema(description = "Último año al que aplica el modelo. Para un solo año, igual a yearFrom",
                example = "2025")
        @NotNull(message = "El año final es obligatorio")
        @Min(value = ColumnLimits.MODEL_YEAR_MIN, message = "El año final no debe ser menor a 1900")
        @PastOrNextYear(message = "El año final debe ser menor o igual al siguiente año")
        Integer yearTo

) {
}
