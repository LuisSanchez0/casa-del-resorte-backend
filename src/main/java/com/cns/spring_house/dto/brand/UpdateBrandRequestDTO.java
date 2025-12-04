package com.cns.spring_house.dto.brand;

import org.springframework.web.multipart.MultipartFile;

public record UpdateBrandRequestDTO(

        String name,
        MultipartFile logo

) { }
