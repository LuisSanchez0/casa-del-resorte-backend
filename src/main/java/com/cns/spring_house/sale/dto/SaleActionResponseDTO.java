package com.cns.spring_house.sale.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.sale.entity.SaleActionType;

/**
 * Motivo, autor y fecha de por que una venta quedo cancelada o revertida.
 * Null en {@code SaleResponseDTO.action()} cuando la venta no tuvo ninguno de
 * los dos eventos.
 */
public record SaleActionResponseDTO(

    UUID id,
    SaleActionType actionType,
    String reason,
    EmployeeResponseDTO performedBy,
    LocalDateTime createdAt

) {

}
