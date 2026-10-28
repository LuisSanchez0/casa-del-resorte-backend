package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.sale.entity.FulfillmentBlockReason;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.TaxMode;

/**
 * Una solicitud de la cola de Control o del Operador, en UNA fila.
 *
 * Es una proyeccion y no la entidad a proposito: cargar {@code SaleDetail} con
 * su venta arrastraria {@code Sale.details} (EAGER) y una consulta por cada
 * venta distinta de la cola. La cola solo necesita estos datos.
 */
public record FulfillmentTaskRow(

    UUID id,
    UUID saleId,
    TaxMode taxMode,
    String saleCustomerName,
    String registeredCustomerName,
    String registeredCustomerNit,
    String plate,
    String sellerFirstName,
    String sellerLastName,
    FulfillmentType type,
    FulfillmentStatus status,
    String note,
    FulfillmentBlockReason blockReason,
    LocalDateTime dispatchedAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    String employeeFirstName,
    String employeeLastName,
    UUID productId,
    String productName,
    String productSku,
    Map<String, Object> attributes,
    UUID familyId,
    String familyName,
    UUID modelId,
    String modelName,
    UUID brandId,
    String brandName,
    Integer quantity,
    /**
     * Precio autorizado por el vendedor. Uso INTERNO: decide si una pieza de
     * origen cabe en el precio. No se publica a Control ni al Operador.
     */
    BigDecimal price,
    String instructions,
    /** Decision del vendedor sobre los colochos; null en lineas anteriores. */
    Boolean requestedCurls,
    Integer requestedCurlCount,
    UUID itemId,
    UUID transformationId,
    UUID jobLogId

) {

}
