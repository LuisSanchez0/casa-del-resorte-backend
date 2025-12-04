package com.cns.spring_house.mapper.model;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.dto.model.CreateModelRequestDTO;
import com.cns.spring_house.dto.model.ModelResponseDTO;
import com.cns.spring_house.dto.model.UpdateModelRequestDTO;
import com.cns.spring_house.model.model.Model;

@Mapper(componentModel = "spring")
public interface ModelMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "brand", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Model toModel(CreateModelRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "brand", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateModelFromDTO(UpdateModelRequestDTO requestDTO, @MappingTarget Model model);

    ModelResponseDTO toResponse(Model model);

}
