package com.cns.spring_house.brand.dto;

import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateBrandRequestDTO(

        @NotBlank(message = "El nombre es obligatorio")
        String name,
        @NotNull(message = "El logo es obligatorio")
        MultipartFile logo

) { }
