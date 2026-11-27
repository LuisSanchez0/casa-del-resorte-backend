package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

/**
 * "Cliente y vehiculo" de una venta ABIERTA: el nombre con el que el taller
 * reconoce el pedido y la placa del vehiculo, sin tocar nada mas de la venta.
 *
 * Trae el estado COMPLETO que el vendedor quiere dejar (nombre y placa) y lo
 * que tenia a la vista al editarlo (`expected*`). Las dos precondiciones se
 * comparan siempre, bajo el cerrojo de la venta: si otra sesion cambio el
 * nombre o la placa desde que se leyeron, 409 y nada se guarda. Un `expected`
 * ausente vale "no habia nada": contra una venta que ya tiene nombre o placa,
 * tambien es 409, asi que no existe una escritura a ciegas.
 *
 * Todo se normaliza como en el alta (trim + mayusculas; en blanco es null), de
 * modo que " juan " y "JUAN" son el mismo nombre para la precondicion.
 *
 * No es la identidad fiscal: NIT o Consumidor Final se deciden al completar la
 * venta y no se pueden elegir aqui.
 */
public record UpdateSaleIdentificationDTO(

    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String customerName,
    /** El nombre que el vendedor tenia a la vista; null si la venta no tenia. */
    String expectedCustomerName,
    @Size(max = ColumnLimits.PLATE,
            message = "La placa no puede superar los {max} caracteres")
    String plate,
    /** La placa que el vendedor tenia a la vista; null si la venta no tenia. */
    String expectedPlate

) {

    public UpdateSaleIdentificationDTO {
        customerName = SaleNames.normalize(customerName);
        expectedCustomerName = SaleNames.normalize(expectedCustomerName);
        plate = SalePlates.normalize(plate);
        expectedPlate = SalePlates.normalize(expectedPlate);
    }

}
