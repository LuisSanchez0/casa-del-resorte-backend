package com.cns.spring_house.attribute.dto;

import java.util.UUID;

import com.cns.spring_house.attribute.entity.AttributeDataType;

public record AttributeResponseDTO(

        UUID id,
        String key,
        AttributeDataType dataType,
        Boolean required

) { }
