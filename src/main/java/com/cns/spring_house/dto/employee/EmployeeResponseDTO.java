package com.cns.spring_house.dto.employee;

import java.time.LocalDate;
import java.util.UUID;

import com.cns.spring_house.dto.workshop.WorkshopResponseDTO;

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
