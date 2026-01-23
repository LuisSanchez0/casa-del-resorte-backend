package com.cns.spring_house.stockitem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record StockItemResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        ProductResponseDTO product,
        BigDecimal price,
        BigDecimal lastCost,
        Integer quantity,
        Integer minThreshold

) { }
