package com.cns.spring_house.job.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SearchJobLogRequestDTO(

    UUID workshop,
    UUID job,
    UUID employee,
    LocalDateTime minDate,
    LocalDateTime maxDate

) {
    
}
