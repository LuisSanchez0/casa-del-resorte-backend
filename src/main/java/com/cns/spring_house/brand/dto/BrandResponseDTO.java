package com.cns.spring_house.brand.dto;

import java.util.UUID;

public record BrandResponseDTO(

        UUID id,
        String name,
        String logoKey

) { }
