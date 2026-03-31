package com.cns.spring_house.family.dto;

import java.util.UUID;

import com.cns.spring_house.family.entity.AttributeDataType;

public record AttributeResponseDTO(

        UUID id,
        String name,
        AttributeDataType dataType,
        Boolean isRequired

) { }
