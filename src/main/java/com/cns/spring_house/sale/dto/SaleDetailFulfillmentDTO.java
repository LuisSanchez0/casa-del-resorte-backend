package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.cns.spring_house.sale.entity.FulfillmentArea;
import com.cns.spring_house.sale.entity.FulfillmentBlockReason;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;

/**
 * Lo que el vendedor necesita para seguir una solicitud: quien la cumple
 * ({@code area}), como ({@code type}) y en que va ({@code status}).
 *
 * {@code type} y {@code area} vienen null en una pieza todavia sin enviar: la
 * ruta se decide al enviar la comanda, con el stock de ese momento.
 */
public record SaleDetailFulfillmentDTO(

    FulfillmentType type,
    FulfillmentArea area,
    FulfillmentStatus status,
    /** Motivo de un bloqueo, p. ej. "Sin materia prima". */
    String note,
    /** Por que esta bloqueada y quien la destraba; null si no lo esta. */
    FulfillmentBlockReason blockReason,
    /**
     * En revision de precio, el minimo que debe cubrir el precio de la linea:
     * el costo real de la pieza de origen que eligio el Operador.
     */
    BigDecimal requiredPrice,
    LocalDateTime dispatchedAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    UUID transformationId,
    UUID jobLogId

) {

}
