package com.cns.spring_house.workshop.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.workshop.dto.CreateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.UpdateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;
import com.cns.spring_house.workshop.entity.Workshop;

@Mapper(componentModel = "spring")
public interface WorkshopMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Workshop toWorkshop(CreateWorkshopRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateWorkshopFromDTO(UpdateWorkshopRequestDTO requestDTO, @MappingTarget Workshop workshop);

    WorkshopResponseDTO toResponse(Workshop workshop);

}
