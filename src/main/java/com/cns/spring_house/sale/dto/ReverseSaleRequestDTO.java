package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Revierte una venta ya COMPLETED, distinto de {@link CancelSaleRequestDTO}:
 * esa venta se emitio y hay que deshacerla, no una que nunca llego a
 * completarse. El motivo es obligatorio por la misma razon: sin el no queda
 * constancia de por que se corrigio una venta ya facturada.
 */
public record ReverseSaleRequestDTO(

    @NotBlank(message = "El motivo de reversion es obligatorio")
    @Size(max = ColumnLimits.SALE_ACTION_REASON,
            message = "El motivo no puede superar los {max} caracteres")
    String reason

) {

    public ReverseSaleRequestDTO {
        reason = reason == null ? null : reason.trim();
    }

}
