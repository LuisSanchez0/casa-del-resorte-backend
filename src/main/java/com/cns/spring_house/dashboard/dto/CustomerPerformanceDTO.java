package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de clientes por venta generada.
 *
 * {@code nit} viaja porque dos clientes pueden llamarse igual y es el
 * identificador con el que el taller los distingue en la factura. No se anade
 * ningun otro dato del cliente: el panel no necesita mas para rankear.
 *
 * {@code purchaseCount} son EXACTAMENTE los documentos que suman en
 * {@code generatedAmount}, calculados ambos sobre ventas COMPLETED sin mirar el
 * estado de cobro.
 */
public record CustomerPerformanceDTO(

        UUID customerId,
        String customerName,
        String nit,
        long purchaseCount,
        BigDecimal generatedAmount

) {

}
