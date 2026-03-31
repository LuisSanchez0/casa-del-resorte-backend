package com.cns.spring_house.family.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.family.dto.AttributeResponseDTO;
import com.cns.spring_house.family.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.family.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.family.entity.Attribute;

@Mapper(componentModel = "spring")
public interface AttributeMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "family", ignore = true)
    Attribute toAttribute(CreateAttributeRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "family", ignore = true)
    void updateAttributeFromDTO(UpdateAttributeRequestDTO requestDTO, @MappingTarget Attribute attribute);
    
    AttributeResponseDTO toResponse(Attribute attribute);

}
