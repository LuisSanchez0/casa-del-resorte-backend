package com.cns.spring_house.dailylog.dto;

import java.time.LocalDate;
import java.util.UUID;

public record SearchDailyLogRequestDTO(

    UUID workshop,
    UUID employee,
    LocalDate minDate,
    LocalDate maxDate

) {
    
}
