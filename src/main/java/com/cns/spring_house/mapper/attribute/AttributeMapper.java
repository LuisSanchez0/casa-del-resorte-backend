package com.cns.spring_house.mapper.attribute;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.dto.attribute.AttributeResponseDTO;
import com.cns.spring_house.dto.attribute.CreateAttributeRequestDTO;
import com.cns.spring_house.dto.attribute.UpdateAttributeRequestDTO;
import com.cns.spring_house.model.attribute.Attribute;

@Mapper(componentModel = "spring")
public interface AttributeMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "family", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Attribute toAttribute(CreateAttributeRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "family", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateAttributeFromDTO(UpdateAttributeRequestDTO requestDTO, @MappingTarget Attribute attribute);
    
    AttributeResponseDTO toResponse(Attribute attribute);

}
