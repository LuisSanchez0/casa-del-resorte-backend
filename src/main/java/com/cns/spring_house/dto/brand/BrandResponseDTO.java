package com.cns.spring_house.dto.brand;

import java.util.UUID;

public record BrandResponseDTO(

        UUID id,
        String name,
        String logoKey

) { }
