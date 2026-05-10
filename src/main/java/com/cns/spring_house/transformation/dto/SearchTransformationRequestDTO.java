package com.cns.spring_house.transformation.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SearchTransformationRequestDTO(

    UUID employee,
    UUID workshop,
    LocalDateTime minDate,
    LocalDateTime maxDate

) {
    
}
