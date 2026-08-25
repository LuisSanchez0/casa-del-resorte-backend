package com.cns.spring_house.transformation.mapper;

import org.mapstruct.Mapper;

import com.cns.spring_house.inventory.mapper.InventoryItemMapper;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.Transformation;

/**
 * Delega el mapeo de InventoryItem en InventoryItemMapper: sin `uses`, MapStruct
 * generaba aquí su propio mapeo por coincidencia de nombres y dejaba en null
 * inventoryId, productId, productName y containerId de cada item.
 */
@Mapper(componentModel = "spring", uses = InventoryItemMapper.class)
public interface TransformationMapper {
    
    TransformationResponseDTO toResponse(Transformation transformation);

}
