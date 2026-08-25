package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.employee.dto.EmployeeResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record SaleResponseDTO(

    UUID id,
    CustomerResponseDTO customer,
    WorkshopResponseDTO workshop,
    EmployeeResponseDTO employee,
    String plate,
    List<SaleDetailResponseDTO> details,
    SaleStatus status,
    InvoiceType invoiceType,
    PaymentStatus paymentStatus,
    PaymentMethod paymentMethod,
    BigDecimal total,
    LocalDateTime completedAt,
    LocalDateTime paidAt,
    LocalDateTime createdAt,
    /** Motivo/autor/fecha de la cancelacion o reversion; null si no aplica. */
    SaleActionResponseDTO action

) {

}
