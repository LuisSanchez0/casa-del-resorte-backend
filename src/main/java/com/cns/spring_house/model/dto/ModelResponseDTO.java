package com.cns.spring_house.model.dto;

import java.util.UUID;

public record ModelResponseDTO(

        UUID id,
        String name,
        Integer year

) { }
