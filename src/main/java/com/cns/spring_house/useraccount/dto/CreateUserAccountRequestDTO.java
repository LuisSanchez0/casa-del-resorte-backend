package com.cns.spring_house.useraccount.dto;

import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserAccountRequestDTO(

        @NotNull(message = "El empleado es obligatorio") UUID employee,
        @NotNull(message = "El role es obligatorio") UUID role,
        @NotBlank(message = "El nombre de usuario es obligatorio")
        @Size(max = ColumnLimits.USERNAME,
                message = "El nombre de usuario no puede superar los {max} caracteres")
        String username,
        @NotBlank(message = "La contraseña es obligatoria") String password

) {
}
