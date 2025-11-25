package com.cns.spring_house.dto.employee;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

public record CreateEmployeeRequestDTO(

        @NotNull(message = "El taller es obligatorio") UUID workshop,
        @NotBlank(message = "El nombre es obligatorio") String firstName,
        @NotBlank(message = "El apellido es obligatorio") String lastName,
        @NotBlank(message = "El DPI es obligatorio") @Size(min = 13, max = 13, message = "El DPI debe ser de {min} digitos") String dpi,
        @Size(min = 8, max = 8, message = "El numero de telefono debe ser de {min} digitos") String phoneNumber,
        @NotNull(message = "La fecha de nacimiento es obligatoria") @Past LocalDate birthDate

) {
}
