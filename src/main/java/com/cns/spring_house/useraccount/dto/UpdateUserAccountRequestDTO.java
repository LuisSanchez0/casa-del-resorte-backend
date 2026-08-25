package com.cns.spring_house.useraccount.dto;

import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

public record UpdateUserAccountRequestDTO(

        UUID role,
        @Size(max = ColumnLimits.USERNAME,
                message = "El nombre de usuario no puede superar los {max} caracteres")
        String username,
        String password,
        Boolean isActive

) {
}
