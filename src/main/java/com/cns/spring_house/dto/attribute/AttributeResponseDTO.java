package com.cns.spring_house.dto.attribute;

import java.util.UUID;

import com.cns.spring_house.model.attribute.AttributeDataType;

public record AttributeResponseDTO(

        UUID id,
        String key,
        AttributeDataType dataType,
        Boolean required

) { }
