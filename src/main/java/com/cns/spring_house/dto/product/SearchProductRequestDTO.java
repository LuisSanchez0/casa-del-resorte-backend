package com.cns.spring_house.dto.product;

import java.util.Map;
import java.util.UUID;

public record SearchProductRequestDTO(

        String q,
        UUID family,
        Map<String, Object> attributes

) { }
