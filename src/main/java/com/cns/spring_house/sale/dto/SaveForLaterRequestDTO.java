package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

/**
 * "Guardar para despues".
 *
 * `customerName` es el nombre de referencia de una venta SIN NIT: si la venta
 * no lo tiene todavia es obligatorio, y si ya lo tiene lo sustituye. Una venta
 * con NIT no lo admite: se reconoce por su cliente registrado. El cuerpo entero
 * es opcional para no romper a quien ya llamaba sin el.
 */
public record SaveForLaterRequestDTO(

    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String customerName

) {

    public SaveForLaterRequestDTO {
        customerName = SaleNames.normalize(customerName);
    }

}
