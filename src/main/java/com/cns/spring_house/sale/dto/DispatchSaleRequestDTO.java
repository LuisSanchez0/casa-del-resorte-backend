package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

/**
 * "Enviar comanda". El cuerpo es opcional y solo COMPLETA lo que falta: el
 * nombre de referencia de una venta sin NIT que todavia no lo tiene, o la
 * placa (opcional) de una venta que no la tiene. Nunca reemplaza un nombre ni
 * una placa ya guardados: un nombre distinto es 409 (otra sesion lo cambio) y
 * una placa distinta, 400. La caja actual guarda ambos antes con
 * `update-identification` y envia sin cuerpo.
 */
public record DispatchSaleRequestDTO(

    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String customerName,
    @Size(max = ColumnLimits.PLATE,
            message = "La placa no puede superar los {max} caracteres")
    String plate

) {

    public DispatchSaleRequestDTO {
        customerName = SaleNames.normalize(customerName);
        plate = SalePlates.normalize(plate);
    }

    /** Sin placa en el cuerpo: la venta conserva la que tenga, o ninguna. */
    public DispatchSaleRequestDTO(String customerName) {
        this(customerName, null);
    }

}
