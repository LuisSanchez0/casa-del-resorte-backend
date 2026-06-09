package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record SaleResponseDTO(

    UUID id,
    CustomerResponseDTO customer,
    WorkshopResponseDTO workshop,
    EmployeeResponseDTO employee,
    List<SaleDetailResponseDTO> details,
    SaleStatus status,
    BigDecimal total,
    LocalDateTime completedAt,
    LocalDateTime createdAt

) {
    
}
