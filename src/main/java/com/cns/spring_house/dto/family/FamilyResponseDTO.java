package com.cns.spring_house.dto.family;

import java.util.UUID;

public record FamilyResponseDTO(

        UUID id,
        String code,
        String name

) { }