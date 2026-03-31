package com.cns.spring_house.transformation.dto;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record TransformationResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        EmployeeResponseDTO employee,
        List<TransformationItemResponseDTO> items

) {
    
}
