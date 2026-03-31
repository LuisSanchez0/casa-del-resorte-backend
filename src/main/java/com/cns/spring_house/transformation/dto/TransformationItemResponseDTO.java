package com.cns.spring_house.transformation.dto;

import java.util.UUID;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.transformation.entity.TransformationItemRole;

public record TransformationItemResponseDTO(

        UUID id,
        TransformationItemRole role,
        InventoryItemResponseDTO item

) {
    
}
