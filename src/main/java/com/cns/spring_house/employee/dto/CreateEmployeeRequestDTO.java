package com.cns.spring_house.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

public record CreateEmployeeRequestDTO(

        @NotNull(message = "El taller es obligatorio")
        UUID workshop,
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = ColumnLimits.EMPLOYEE_NAME,
                message = "El nombre no puede superar los {max} caracteres")
        String firstName,
        @NotBlank(message = "El apellido es obligatorio")
        @Size(max = ColumnLimits.EMPLOYEE_NAME,
                message = "El apellido no puede superar los {max} caracteres")
        String lastName,
        @NotBlank(message = "El DPI es obligatorio")
        @Size(min = ColumnLimits.EMPLOYEE_DPI, max = ColumnLimits.EMPLOYEE_DPI, message = "El DPI debe ser de {min} digitos")
        String dpi,
        @Size(min = ColumnLimits.EMPLOYEE_PHONE, max = ColumnLimits.EMPLOYEE_PHONE, message = "El numero de telefono debe ser de {min} digitos")
        String phoneNumber,
        @NotNull(message = "La fecha de nacimiento es obligatoria")
        @Past(message = "La fecha de nacimiento debe ser pasada")
        LocalDate birthDate

) {
}
