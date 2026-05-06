package com.cns.spring_house.job.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;

public record JobLogResponseDTO(

    UUID id,
    EmployeeResponseDTO employee,
    JobResponseDTO job,
    LocalDateTime performedDate

) {
    
}
