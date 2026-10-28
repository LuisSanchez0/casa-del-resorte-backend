package com.cns.spring_house.sale.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

/**
 * "Enviar comanda". El cuerpo es opcional: solo hace falta para darle a una
 * venta sin NIT el nombre de referencia con el que Control y el Operador
 * sabran de quien es el pedido, o la placa a una venta que envia un servicio
 * sin tenerla todavia.
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

    /** Sin placa: la venta ya la tiene o no envia ningun servicio. */
    public DispatchSaleRequestDTO(String customerName) {
        this(customerName, null);
    }

}
