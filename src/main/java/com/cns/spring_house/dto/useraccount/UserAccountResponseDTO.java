package com.cns.spring_house.dto.useraccount;

import java.util.UUID;

import com.cns.spring_house.dto.employee.EmployeeResponseDTO;
import com.cns.spring_house.dto.role.RoleResponseDTO;

public record UserAccountResponseDTO(

        UUID id,
        RoleResponseDTO role,
        EmployeeResponseDTO employee,
        String username,
        Boolean isActive

) {
}
