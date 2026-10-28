package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** El Operador no puede cumplir la solicitud por ahora (p. ej. sin materia prima). */
public record BlockFulfillmentRequestDTO(

    @NotBlank(message = "Indique el motivo")
    @Size(max = ColumnLimits.FULFILLMENT_NOTE,
            message = "El motivo no puede superar los {max} caracteres")
    String reason

) {

    public BlockFulfillmentRequestDTO {
        reason = reason == null ? null : reason.trim();
    }

}
