package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;

/**
 * Fila de la pantalla de cartera cambiaria: lo justo para decidir a quien se le
 * cobra, cuanto y desde cuando.
 *
 * Deliberadamente NO lleva `details`. La cartera es un listado historico que
 * puede tener cientos de documentos, y {@code Sale.details} es EAGER: devolver
 * SaleResponseDTO obligaba a traer cada linea, su producto, su familia y su
 * InventoryItem solo para pintar un total. El detalle completo se pide al abrir
 * la venta, con GET /v1/sales/{id}.
 */
public record ExchangeInvoiceSummaryDTO(

    UUID id,
    CustomerResponseDTO customer,
    String plate,
    InvoiceType invoiceType,
    PaymentStatus paymentStatus,
    PaymentMethod paymentMethod,
    BigDecimal total,
    LocalDateTime completedAt,
    LocalDateTime paidAt

) {

    /**
     * Constructor que usa la consulta de cartera. Recibe el cliente aplanado
     * porque la proyeccion selecciona columnas sueltas, no la entidad Customer:
     * traerla entera volveria a cargar de mas.
     *
     * `customer` es opcional en el modelo (una venta de mostrador puede no
     * tenerlo), y un LEFT JOIN sin cliente devuelve todo a null; en ese caso el
     * resumen lleva null y no un cliente vacio con tres nulls dentro.
     */
    public ExchangeInvoiceSummaryDTO(
            UUID id,
            UUID customerId,
            String customerNit,
            String customerName,
            String plate,
            InvoiceType invoiceType,
            PaymentStatus paymentStatus,
            PaymentMethod paymentMethod,
            BigDecimal total,
            LocalDateTime completedAt,
            LocalDateTime paidAt) {
        this(
                id,
                customerId == null
                        ? null
                        : new CustomerResponseDTO(customerId, customerNit, customerName),
                plate,
                invoiceType,
                paymentStatus,
                paymentMethod,
                total,
                completedAt,
                paidAt);
    }

}
