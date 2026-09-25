package com.cns.spring_house.transformation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record TransformationResponseDTO(

        UUID id,
        WorkshopResponseDTO workshop,
        EmployeeResponseDTO employee,
        LocalDateTime performedDate,
        List<TransformationItemResponseDTO> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) Set<UUID> createdInventoryIds

) {
    public TransformationResponseDTO(UUID id, WorkshopResponseDTO workshop,
            EmployeeResponseDTO employee, LocalDateTime performedDate,
            List<TransformationItemResponseDTO> items) {
        this(id, workshop, employee, performedDate, items, null);
    }

    public TransformationResponseDTO withCreatedInventoryIds(Set<UUID> ids) {
        return new TransformationResponseDTO(id, workshop, employee,
                performedDate, items, Set.copyOf(ids));
    }
}
