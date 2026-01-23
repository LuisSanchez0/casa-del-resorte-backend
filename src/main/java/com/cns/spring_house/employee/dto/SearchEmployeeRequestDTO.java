package com.cns.spring_house.employee.dto;

import java.util.UUID;

public record SearchEmployeeRequestDTO(

        String q,
        UUID workshop

) {
}
