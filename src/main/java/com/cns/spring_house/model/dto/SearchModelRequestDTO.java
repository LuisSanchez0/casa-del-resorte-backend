package com.cns.spring_house.model.dto;

import java.util.UUID;

public record SearchModelRequestDTO(

        UUID brand,
        String name,
        Integer year

) { }
