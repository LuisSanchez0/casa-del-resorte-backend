package com.cns.spring_house.inventory.dto;

import java.util.List;

/** Operation metadata, never reconstructed from quantities or latest-item queries. */
public record InventoryMutationDetailsDTO(
        boolean productBarcodeFirstUse,
        List<InventoryItemResponseDTO> createdItems) {
    public InventoryMutationDetailsDTO {
        createdItems = List.copyOf(createdItems);
    }
}
