package com.cns.spring_house.brand.dto;

import org.springframework.web.multipart.MultipartFile;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBrandRequestDTO(

        @NotBlank(message = "El nombre es obligatorio")
@Size(max = ColumnLimits.BRAND_NAME,
                message = "El nombre de la marca no puede superar los {max} caracteres")
        String name,
        @NotNull(message = "El logo es obligatorio")
        MultipartFile logo

) { }
