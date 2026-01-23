package com.cns.spring_house.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record EmployeeResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        String firstName,
        String lastName,
        String dpi,
        String phoneNumber,
        LocalDate birthDate

) {
}
