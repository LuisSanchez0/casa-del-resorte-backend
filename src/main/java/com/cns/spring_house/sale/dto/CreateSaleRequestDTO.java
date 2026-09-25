package com.cns.spring_house.sale.dto;

import java.util.Locale;
import java.util.UUID;

import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Size;

/**
 * Alta de una venta con su identidad fiscal.
 *
 * Las reglas cruzadas entre los campos fiscales viven en el servicio, igual
 * que la de factura/forma de pago: dependen de `taxMode` y Bean Validation no
 * las expresa sin duplicarlas.
 *
 *   NIT        -> `customer` obligatorio, `customerName` ausente
 *   UNRESOLVED -> sin `customer`; `customerName` opcional mientras es un
 *                 borrador (se exige al guardarla para despues)
 *   CF         -> RECHAZADO: Consumidor Final se elige al emitir, en la misma
 *                 transaccion que completa la venta, nunca en un carrito
 *
 * `taxMode` ausente es el contrato anterior, que solo sabia de ventas con
 * cliente: se trata como NIT y por tanto sigue exigiendo `customer`. Nunca se
 * interpreta como CF.
 */
public record CreateSaleRequestDTO(

    /** `Customer.id`, jamas el NIT; solo en una venta con NIT. */
    UUID customer,
    /** Opcional: identifica el vehiculo atendido, si la venta viene de uno. */
    @Size(max = ColumnLimits.PLATE,
            message = "La placa no puede superar los {max} caracteres")
    String plate,
    TaxMode taxMode,
    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String customerName

) {

    /**
     * Misma normalizacion que VehicleMovement (trim + mayusculas con Locale.ROOT)
     * para que la placa de una venta y la de un ingreso sean comparables. Una
     * placa en blanco se guarda como null: es un dato opcional, no una cadena
     * vacia.
     *
     * El nombre se normaliza igual que el del maestro de clientes (mayusculas),
     * para que una busqueda por nombre no dependa de como se tecleo. En blanco es
     * null: "sin nombre", que en CF se presenta como Consumidor Final y en
     * UNRESOLVED el servicio rechaza.
     */
    public CreateSaleRequestDTO {
        plate = plate == null || plate.isBlank()
                ? null
                : plate.trim().toUpperCase(Locale.ROOT);
        customerName = SaleNames.normalize(customerName);
    }

    /** Contrato anterior: una venta con NIT para un cliente registrado. */
    public CreateSaleRequestDTO(UUID customer, String plate) {
        this(customer, plate, null, null);
    }

}
