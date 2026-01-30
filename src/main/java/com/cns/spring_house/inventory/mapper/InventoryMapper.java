package com.cns.spring_house.inventory.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.inventory.dto.CreateInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.InventoryResponseDTO;
import com.cns.spring_house.inventory.dto.UpdateInventoryRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;

@Mapper(componentModel = "spring")
public interface InventoryMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore =  true)
    @Mapping(target = "product", ignore =  true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Inventory toInventory(CreateInventoryRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore =  true)
    @Mapping(target = "product", ignore =  true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateInventoryFromDTO(UpdateInventoryRequestDTO requestDTO, @MappingTarget Inventory inventory);

    InventoryResponseDTO toResponse(Inventory inventory);

}
