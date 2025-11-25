package com.cns.spring_house.dto.useraccount;

import com.cns.spring_house.dto.employee.EmployeeResponseDTO;
import com.cns.spring_house.dto.role.RoleResponseDTO;

public record UserAccountReponseDTO(

        RoleResponseDTO role,
        EmployeeResponseDTO employee,
        String username,
        String password,
        Boolean isActive

) {
}
