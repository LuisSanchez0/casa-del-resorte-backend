package com.cns.spring_house.inventory.dto;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.inventory.entity.InventoryItemStatus;

public record InventoryItemResponseDTO(

        UUID id,
        InventoryItemResponseDTO container,
        InventoryItemStatus status,
        List<InventoryItemResponseDTO> containedItems
        
) {
    
}
