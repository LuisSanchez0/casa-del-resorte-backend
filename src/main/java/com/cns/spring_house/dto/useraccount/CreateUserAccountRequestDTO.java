package com.cns.spring_house.dto.useraccount;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserAccountRequestDTO(

        @NotNull(message = "El empleado es obligatorio") UUID employee,
        @NotNull(message = "El role es obligatorio") UUID role,
        @NotBlank(message = "El nombre de usuario es obligatorio") String username,
        @NotBlank(message = "La contraseña es obligatoria") String password

) {
}
