package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;

/**
 * `workshop` se conserva por compatibilidad con los clientes que ya lo envian,
 * pero el servicio lo IGNORA: el alcance sale del token, nunca de la peticion.
 */
public record SearchSaleRequestDTO(

    /** Texto libre: coincide contra el nombre O el nit del cliente. */
    String customer,
    /**
     * Cliente exacto, por identificador. Es lo que usa la caja cuando ya
     * resolvio el Customer y necesita saber si tiene ventas abiertas: comparar
     * el nit como texto relacionaria ventas de clientes distintos.
     */
    UUID customerId,
    String plate,
    BigDecimal minTotal,
    BigDecimal maxTotal,
    LocalDateTime minDate,
    LocalDateTime maxDate,
    UUID workshop,
    UUID employee,
    SaleStatus status,
    InvoiceType invoiceType,
    PaymentStatus paymentStatus,
    /**
     * Opcional. `true` son las ventas guardadas para despues a proposito;
     * `false`, los carritos abiertos que nunca se guardaron. Sin el, ambas.
     */
    Boolean savedForLater

) {

    /** Contrato anterior, sin distinguir guardadas de borradores. */
    public SearchSaleRequestDTO(
            String customer, UUID customerId, String plate,
            BigDecimal minTotal, BigDecimal maxTotal,
            LocalDateTime minDate, LocalDateTime maxDate,
            UUID workshop, UUID employee, SaleStatus status,
            InvoiceType invoiceType, PaymentStatus paymentStatus) {
        this(customer, customerId, plate, minTotal, maxTotal, minDate, maxDate,
                workshop, employee, status, invoiceType, paymentStatus, null);
    }

}
