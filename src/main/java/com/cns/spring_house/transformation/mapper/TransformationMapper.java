package com.cns.spring_house.transformation.mapper;

import org.mapstruct.Mapper;

import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.Transformation;

@Mapper(componentModel = "spring")
public interface TransformationMapper {
    
    TransformationResponseDTO toResponse(Transformation transformation);

}
