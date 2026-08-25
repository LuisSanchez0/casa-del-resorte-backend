package com.cns.spring_house.brand.dto;

import org.springframework.web.multipart.MultipartFile;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

public record UpdateBrandRequestDTO(

        @Size(max = ColumnLimits.BRAND_NAME,
                message = "El nombre de la marca no puede superar los {max} caracteres")
        String name,
        MultipartFile logo

) { }
