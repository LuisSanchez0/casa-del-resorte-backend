package com.cns.spring_house.sale.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.sale.entity.FulfillmentArea;
import com.cns.spring_house.sale.entity.FulfillmentBlockReason;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;

/**
 * Una solicitud tal como la ven Control y el Operador: de quien es el pedido,
 * que hay que entregar o fabricar, y en que va.
 *
 * No lleva precio ni datos de cobro a proposito: quien cumple la solicitud no
 * decide nada de eso y no necesita verlo.
 */
public record FulfillmentTaskResponseDTO(

    /** Id de la linea de venta: la solicitud ES la linea. */
    UUID id,
    UUID saleId,
    /** Nombre del cliente o de referencia del pedido; null si no tiene. */
    String customerName,
    /** NIT del cliente registrado, solo cuando la venta lo tiene. */
    String customerNit,
    String plate,
    /** Vendedor que tomo el pedido. */
    String sellerName,
    FulfillmentType type,
    FulfillmentArea area,
    FulfillmentStatus status,
    String note,
    /**
     * Por que esta bloqueada. En PRICE_REVIEW la espera es del vendedor; el
     * Operador no ve ni decide importes.
     */
    FulfillmentBlockReason blockReason,
    LocalDateTime dispatchedAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    /** Quien la tomo o la entrego. */
    String assignedTo,
    FulfillmentProductDTO product,
    /**
     * Si la pieza pedida lleva colochos, como lo decidio el vendedor: TRUE lleva,
     * FALSE no lleva (nadie le agrega), null no se sabe (linea anterior a la
     * regla). Ver {@code RequestedCurls}.
     */
    Boolean requestedCurls,
    /**
     * Cuantos colochos pidio el vendedor. Null si no lleva o si lleva pero la
     * cantidad quedo abierta: no es cero.
     */
    Integer requestedCurlCount,
    /** La familia de la pieza pedida maneja colochos. */
    boolean curlCountApplies,
    Integer quantity,
    String instructions,
    /**
     * Unidad fisica de la solicitud: la reservada para Control mientras no se
     * entrega, la entregada o la fabricada despues. Null si aun no hay.
     */
    UUID itemId,
    String itemBarcode,
    /**
     * Solo en STOCK_RELEASE: unidades LIBRES del producto pedido. No cuenta la
     * unidad ya reservada para esta solicitud —esa ya es de la venta—, asi que
     * son las que Control podria entregar en su lugar. Cero no impide liberar
     * la reservada.
     */
    Integer availableStock,
    UUID transformationId,
    UUID jobLogId,
    /**
     * Como va la venta de esta solicitud EN ESTA AREA: permite agrupar la cola
     * por venta y decir "1 de 3 listas" sin traer las ya cumplidas.
     */
    FulfillmentSaleProgressDTO saleProgress

) {

}
