package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Revierte una venta ya COMPLETED, distinto de {@link CancelSaleRequestDTO}:
 * esa venta se emitio y hay que deshacerla, no una que nunca llego a
 * completarse. El motivo es obligatorio por la misma razon: sin el no queda
 * constancia de por que se corrigio una venta ya facturada.
 *
 * `refund` responde la unica pregunta que el sistema no sabia contestar: si al
 * revertir se devolvio dinero de verdad. Es OPCIONAL, y su ausencia significa
 * exactamente "no se devolvio": revertir y devolver son cosas distintas y una
 * no se deduce de la otra. El mismo `reason` sirve a las dos —es la misma
 * explicacion del mismo acto— para no obligar a teclear dos veces lo mismo.
 */
public record ReverseSaleRequestDTO(

    @NotBlank(message = "El motivo de reversion es obligatorio")
    @Size(max = ColumnLimits.SALE_ACTION_REASON,
            message = "El motivo no puede superar los {max} caracteres")
    String reason,

    /** Ausente = no hubo devolucion de dinero. */
    @Valid
    RefundRequestDTO refund

) {

    public ReverseSaleRequestDTO {
        reason = reason == null ? null : reason.trim();
    }

    /**
     * Reversion SIN devolucion de dinero, que es el caso por defecto.
     *
     * Existe para que "no se devolvio nada" se escriba como lo que es y no como
     * un `null` suelto en la posicion de un argumento.
     */
    public ReverseSaleRequestDTO(String reason) {
        this(reason, null);
    }

    /** Sin objeto anidado no hay devolucion que registrar. */
    public boolean hasRefund() {
        return refund != null;
    }

}
