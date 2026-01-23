package com.cns.spring_house.brand.mapper;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import com.cns.spring_house.brand.dto.BrandResponseDTO;
import com.cns.spring_house.brand.dto.CreateBrandRequestDTO;
import com.cns.spring_house.brand.dto.UpdateBrandRequestDTO;
import com.cns.spring_house.brand.entity.Brand;

@Mapper(componentModel = "spring")
public interface BrandMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "logoKey", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Brand toBrand(CreateBrandRequestDTO requestDTO);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "logoKey", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateBrandFromDTO(UpdateBrandRequestDTO requestDTO, @MappingTarget Brand brand);

    BrandResponseDTO toResponse(Brand brand);

}
