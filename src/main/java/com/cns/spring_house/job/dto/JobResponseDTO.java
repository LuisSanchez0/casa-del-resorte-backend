package com.cns.spring_house.job.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record JobResponseDTO(

        UUID id,
        BigDecimal price,
        WorkshopResponseDTO workshop,
        ProductResponseDTO product

) {
    
}
