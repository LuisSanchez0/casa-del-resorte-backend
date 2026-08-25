package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;

/**
 * Sugerencia del buscador de facturas ya emitidas: lo justo para reconocer cual
 * de todas es la que se busca antes de abrirla.
 *
 * Deliberadamente NO lleva `details`, igual que
 * {@link ExchangeInvoiceSummaryDTO}: {@code Sale.details} es EAGER, de modo que
 * devolver SaleResponseDTO obligaria a traer cada linea —con su producto, su
 * familia y su InventoryItem— solo para pintar un total en una lista
 * desplegable. Al elegir una sugerencia el cliente pide la venta entera con
 * GET /v1/sales/{id}.
 *
 * Es un tipo aparte y no el de la cartera cambiaria porque son dos lecturas
 * distintas: la cartera lista SOLO documentos a credito y se pagina, esta
 * sugiere CUALQUIER venta completada y siempre viene acotada a un puñado de
 * resultados. Que hoy compartan forma no las obliga a evolucionar juntas.
 */
public record SaleSummaryDTO(

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
     * Constructor que usa la consulta de sugerencias. Recibe el cliente aplanado
     * porque la proyeccion selecciona columnas sueltas, no la entidad Customer:
     * traerla entera volveria a cargar de mas.
     *
     * `customer` es opcional en el modelo (una venta de mostrador puede no
     * tenerlo), y un LEFT JOIN sin cliente devuelve todo a null; en ese caso el
     * resumen lleva null y no un cliente vacio con tres nulls dentro.
     */
    public SaleSummaryDTO(
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
