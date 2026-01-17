package com.cns.spring_house.dto.leaf;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateLeafRequestDTO(
    @NotBlank(message = "El número de la hoja es obligatorio")
    @Pattern(regexp = "^[A-Za-z0-9]{1,10}$", message = "El número de la hoja solo puede contener letras y números")
    String leafNumber,
    @NotNull(message = "El ancho es obligatorio")
    @Positive(message = "El ancho debe ser mayor a 0")
    BigDecimal width,
    @NotNull(message = "La primera longitud es obligatoria")
    @Positive(message = "La primera longitud debe ser mayor a 0")
    BigDecimal firstLength,
    @Positive(message = "La segunda longitud debe ser mayor a 0")
    BigDecimal secondLength,
    @NotNull(message = "Se debe indicar si la hoja tiene agujero o no")
    Boolean hasHole
) {}