package com.cns.spring_house.servicedetails.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record ServiceDetailsResponseDTO(

        UUID id,
        BigDecimal price,
        WorkshopResponseDTO workshop,
        ProductResponseDTO product

) {
    
}
