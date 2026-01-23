package com.cns.spring_house.brand.dto;

import org.springframework.web.multipart.MultipartFile;

public record UpdateBrandRequestDTO(

        String name,
        MultipartFile logo

) { }
