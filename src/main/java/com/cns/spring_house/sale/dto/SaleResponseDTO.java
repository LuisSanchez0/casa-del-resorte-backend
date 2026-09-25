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
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public record SaleResponseDTO(

    UUID id,
    /** Solo con NIT; null en Consumidor Final y en una venta sin definir. */
    CustomerResponseDTO customer,
    /**
     * Identidad fiscal de la venta, independiente de `status`. Con ella y
     * `customerName` la venta se presenta sin consultar el maestro de clientes
     * cuando no factura a uno.
     */
    TaxMode taxMode,
    /**
     * Nombre escrito en la venta: el de referencia en UNRESOLVED, el del
     * documento en CF (null = "Consumidor Final") y, en una venta con NIT ya
     * cerrada, el nombre del cliente congelado al emitirla o anularla. Null en
     * una venta con NIT todavia abierta.
     */
    String customerName,
    /**
     * NIT congelado de una venta con NIT ya cerrada; null en cualquier otro
     * caso. Es la identidad historica: no cambia si despues se edita el
     * cliente.
     */
    String customerNit,
    /** El vendedor la guardo para despues (frente a un carrito sin guardar). */
    boolean savedForLater,
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
