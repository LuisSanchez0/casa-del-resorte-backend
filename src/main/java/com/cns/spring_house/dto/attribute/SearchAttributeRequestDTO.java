package com.cns.spring_house.dto.attribute;

import java.util.UUID;

import com.cns.spring_house.model.attribute.AttributeDataType;

public record SearchAttributeRequestDTO(

        UUID family,
        String key,
        AttributeDataType type,
        Boolean required

) { }
