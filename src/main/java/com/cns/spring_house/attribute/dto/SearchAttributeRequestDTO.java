package com.cns.spring_house.attribute.dto;

import java.util.UUID;

import com.cns.spring_house.attribute.entity.AttributeDataType;

public record SearchAttributeRequestDTO(

        UUID family,
        String name,
        AttributeDataType type,
        Boolean required

) { }
