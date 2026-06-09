package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;

public record SaleDetailResponseDTO(

    UUID id,
    ProductResponseDTO product,
    InventoryItemResponseDTO item,
    Integer quantity,
    BigDecimal price,
    BigDecimal subTotal

) {
    
}
