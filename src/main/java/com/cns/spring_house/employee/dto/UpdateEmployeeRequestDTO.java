package com.cns.spring_house.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

public record UpdateEmployeeRequestDTO(

        UUID workshop,
        @Size(max = ColumnLimits.EMPLOYEE_NAME,
                message = "El nombre no puede superar los {max} caracteres")
        String firstName,
        @Size(max = ColumnLimits.EMPLOYEE_NAME,
                message = "El apellido no puede superar los {max} caracteres")
        String lastName,
        @Size(min = ColumnLimits.EMPLOYEE_DPI, max = ColumnLimits.EMPLOYEE_DPI, message = "El DPI debe ser de {min} digitos")
        String dpi,
        @Size(min = ColumnLimits.EMPLOYEE_PHONE, max = ColumnLimits.EMPLOYEE_PHONE, message = "El numero de telefono debe ser de {min} digitos")
        String phoneNumber,
        LocalDate birthDate

) {
}
