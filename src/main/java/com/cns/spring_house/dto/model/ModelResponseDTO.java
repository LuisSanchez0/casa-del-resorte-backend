package com.cns.spring_house.dto.model;

import java.util.UUID;

public record ModelResponseDTO(

        UUID id,
        String name,
        Integer year

) { }
