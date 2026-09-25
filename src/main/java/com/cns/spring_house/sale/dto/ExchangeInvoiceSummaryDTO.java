package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.TaxMode;

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
    /** Con NIT, Consumidor Final o sin definir; decide como leer lo demas. */
    TaxMode taxMode,
    /** Nombre escrito en la venta: el de CF, el de referencia o el congelado. */
    String customerName,
    /** NIT congelado de una venta con NIT ya cerrada; null en CF. */
    String customerNit,
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
     *
     * Por eso viajan tambien el modo fiscal y el nombre escrito en la venta: sin
     * ellos una venta a Consumidor Final y una sin cliente serian indistinguibles.
     */
    public ExchangeInvoiceSummaryDTO(
            UUID id,
            UUID customerId,
            String customerNit,
            String registeredCustomerName,
            TaxMode taxMode,
            String customerName,
            String saleCustomerNit,
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
                        : new CustomerResponseDTO(customerId, customerNit, registeredCustomerName),
                taxMode,
                customerName,
                saleCustomerNit,
                plate,
                invoiceType,
                paymentStatus,
                paymentMethod,
                total,
                completedAt,
                paidAt);
    }

}
