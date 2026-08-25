package com.cns.spring_house.family.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

public record UpdateFamilyRequestDTO(

        @Size(max = ColumnLimits.FAMILY_CODE,
                message = "El codigo de la familia no puede superar los {max} caracteres")
        String code,
        @Size(max = ColumnLimits.FAMILY_NAME,
                message = "El nombre de la familia no puede superar los {max} caracteres")
        String name,
        Boolean requiresModel

) {
}
