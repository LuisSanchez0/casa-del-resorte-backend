package com.cns.spring_house.workshop.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkshopRequestDTO(

        @NotBlank(message = "El nombre del taller es obligatorio") String name,
        @NotBlank(message = "La direccion del taller es obligatoria") String address

) {
}
