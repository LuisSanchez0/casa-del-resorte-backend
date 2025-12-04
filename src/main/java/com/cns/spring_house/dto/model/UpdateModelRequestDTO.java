package com.cns.spring_house.dto.model;

import java.util.UUID;

import com.cns.spring_house.validators.pastornextyear.PastOrNextYear;

import jakarta.validation.constraints.Min;

public record UpdateModelRequestDTO(

        UUID brand,
        String name,
        @Min(value = 1900, message = "El año no debe ser mayor a 1900")
        @PastOrNextYear(message = "El año debe ser menor o igual al siguiente año")
        Integer year

) { }
