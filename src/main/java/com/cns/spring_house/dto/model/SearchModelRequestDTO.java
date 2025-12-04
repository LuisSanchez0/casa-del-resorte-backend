package com.cns.spring_house.dto.model;

import java.util.UUID;

public record SearchModelRequestDTO(

        UUID brand,
        String name,
        Integer year

) { }
