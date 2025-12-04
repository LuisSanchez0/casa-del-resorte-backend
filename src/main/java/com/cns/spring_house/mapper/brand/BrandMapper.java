package com.cns.spring_house.mapper.brand;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.cns.spring_house.dto.brand.BrandResponseDTO;
import com.cns.spring_house.dto.brand.CreateBrandRequestDTO;
import com.cns.spring_house.model.brand.Brand;

@Mapper(componentModel = "spring")
public interface BrandMapper {
    
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "logoKey", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Brand toBrand(CreateBrandRequestDTO requestDTO);

    BrandResponseDTO toResponse(Brand brand);

}
