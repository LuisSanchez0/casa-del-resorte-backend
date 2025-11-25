package com.cns.spring_house.dto.employee;

import java.util.UUID;

public record SearchEmployeeRequestDTO(

        String q,
        UUID workshop

) {
}
