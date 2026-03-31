package com.cns.spring_house.inventory.mapper;

import org.mapstruct.Mapper;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.inventory.entity.InventoryItem;

@Mapper(componentModel = "spring")
public interface InventoryItemMapper {
    
    InventoryItemResponseDTO toResponse(InventoryItem unitInventory);

}
