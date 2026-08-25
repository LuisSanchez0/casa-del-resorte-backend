package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Anula una venta PENDING conservandola como historial. El motivo ya no es
 * opcional: sin el, el historial no puede explicar por que se anulo.
 */
public record CancelSaleRequestDTO(

    @NotBlank(message = "El motivo de anulacion es obligatorio")
    @Size(max = ColumnLimits.SALE_ACTION_REASON,
            message = "El motivo no puede superar los {max} caracteres")
    String reason

) {

    public CancelSaleRequestDTO {
        reason = reason == null ? null : reason.trim();
    }

}
