package com.cns.spring_house.mapper.stockitem;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.dto.stockitem.CreateStockItemRequestDTO;
import com.cns.spring_house.dto.stockitem.StockItemResponseDTO;
import com.cns.spring_house.dto.stockitem.UpdateStockItemRequestDTO;
import com.cns.spring_house.model.stockitem.StockItem;

@Mapper(componentModel = "spring")
public interface StockItemMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore =  true)
    @Mapping(target = "product", ignore =  true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    StockItem toStockItem(CreateStockItemRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "workshop", ignore =  true)
    @Mapping(target = "product", ignore =  true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateStockItemFromDTO(UpdateStockItemRequestDTO requestDTO, @MappingTarget StockItem stockItem);

    StockItemResponseDTO toResponse(StockItem stockItem);

}
