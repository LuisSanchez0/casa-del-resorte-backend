package com.cns.spring_house.job.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SearchJobRequestDTO(

    BigDecimal minPrice,
    BigDecimal maxPrice,
    UUID workshop,
    String name

) {
    
}
