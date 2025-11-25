package com.cns.spring_house.mapper.workshop;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.dto.workshop.CreateWorkshopRequestDTO;
import com.cns.spring_house.dto.workshop.UpdateWorkshopRequestDTO;
import com.cns.spring_house.dto.workshop.WorkshopResponseDTO;
import com.cns.spring_house.model.workshop.Workshop;

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
