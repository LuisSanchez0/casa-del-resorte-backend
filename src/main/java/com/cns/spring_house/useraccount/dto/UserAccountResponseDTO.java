package com.cns.spring_house.useraccount.dto;

import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.role.dto.RoleResponseDTO;

public record UserAccountResponseDTO(

        UUID id,
        RoleResponseDTO role,
        EmployeeResponseDTO employee,
        String username,
        Boolean isActive

) {
}
