package com.cns.spring_house.dailylog.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record DailyLogResponseDTO(

    UUID id,
    WorkshopResponseDTO workshop,
    EmployeeResponseDTO employee,
    String description,
    LocalDate performedDate

) {
    
}
