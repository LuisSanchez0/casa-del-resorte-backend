package com.cns.spring_house.dto.stockitem;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.dto.product.ProductResponseDTO;
import com.cns.spring_house.dto.workshop.WorkshopResponseDTO;

public record StockItemResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        ProductResponseDTO product,
        BigDecimal price,
        BigDecimal lastCost,
        Integer quantity,
        Integer minThreshold

) { }
