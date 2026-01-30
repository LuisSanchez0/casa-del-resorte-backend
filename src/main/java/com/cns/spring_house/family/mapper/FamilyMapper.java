package com.cns.spring_house.family.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.family.dto.CreateFamilyRequestDTO;
import com.cns.spring_house.family.dto.FamilyResponseDTO;
import com.cns.spring_house.family.dto.UpdateFamilyRequestDTO;
import com.cns.spring_house.family.entity.Family;

@Mapper(componentModel = "spring")
public interface FamilyMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "attributes", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Family toFamily(CreateFamilyRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "attributes", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateFamilyFromDTO(UpdateFamilyRequestDTO requestDTO, @MappingTarget Family family);

    FamilyResponseDTO toResponse(Family family);

}
