package com.cns.spring_house.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Size;

public record UpdateEmployeeRequestDTO(

        UUID workshop,
        String firstName,
        String lastName,
        @Size(min = 13, max = 13, message = "El DPI debe ser de {min} digitos")
        String dpi,
        @Size(min = 8, max = 8, message = "El numero de telefono debe ser de {min} digitos")
        String phoneNumber,
        LocalDate birthDate

) {
}
