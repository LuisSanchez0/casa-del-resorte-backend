package com.cns.spring_house.inventory.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record InventoryResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        ProductResponseDTO product,
        BigDecimal price,
        BigDecimal cost,
        Integer stock,
        Integer minStock

) { }
