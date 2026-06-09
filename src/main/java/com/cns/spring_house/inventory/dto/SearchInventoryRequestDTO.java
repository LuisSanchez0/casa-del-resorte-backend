package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SearchInventoryRequestDTO(

        UUID workshop,
        UUID product,
        BigDecimal minCost,
        BigDecimal maxCost,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Boolean isLowStock

) { }
