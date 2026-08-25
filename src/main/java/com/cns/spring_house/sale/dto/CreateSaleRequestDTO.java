package com.cns.spring_house.sale.dto;

import java.util.Locale;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

public record CreateSaleRequestDTO(

    @NotNull(message = "El cliente es obligatorio")
    UUID customer,
    /** Opcional: identifica el vehiculo atendido, si la venta viene de uno. */
    @Size(max = ColumnLimits.PLATE,
            message = "La placa no puede superar los {max} caracteres")
    String plate

) {

    /**
     * Misma normalizacion que VehicleMovement (trim + mayusculas con Locale.ROOT)
     * para que la placa de una venta y la de un ingreso sean comparables. Una
     * placa en blanco se guarda como null: es un dato opcional, no una cadena
     * vacia.
     */
    public CreateSaleRequestDTO {
        plate = plate == null || plate.isBlank()
                ? null
                : plate.trim().toUpperCase(Locale.ROOT);
    }

}
