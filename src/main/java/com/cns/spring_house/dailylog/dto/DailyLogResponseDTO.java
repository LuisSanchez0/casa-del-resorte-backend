package com.cns.spring_house.dailylog.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

/**
 * De la venta solo sale su id: es la referencia que la secretaria ve impresa en
 * la factura, y devolver la venta entera arrastraria sus detalles —con producto,
 * familia e item de inventario— a una respuesta que no los necesita.
 */
public record DailyLogResponseDTO(

        UUID id,
        UUID saleId,
        WorkshopResponseDTO workshop,
        EmployeeResponseDTO employee,
        List<DailyLogLineResponseDTO> products,
        List<DailyLogLineResponseDTO> services,
        String description,
        LocalDate performedDate,
        LocalDateTime createdAt

) {
}
