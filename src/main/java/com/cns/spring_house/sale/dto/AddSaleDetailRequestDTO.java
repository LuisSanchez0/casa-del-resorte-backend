package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddSaleDetailRequestDTO(

    /**
     * SIEMPRE Product.id, tambien para servicios: el handler de JOB resuelve el
     * Job por producto y taller. Antes este campo se interpretaba como Product.id
     * en el servicio y como Job.id en el handler, y ningun valor podia satisfacer
     * a los dos.
     */
    @NotNull(message = "El id del producto es obligatorio")
    UUID product,
    UUID item,
    UUID workResult,
    @Max(value = ColumnLimits.SMALLINT_MAX,
            message = "La cantidad maxima permitida es {value}")
    Integer quantity,
    /**
     * Precio especial opcional. El tope inferior real (el costo de adquisicion) se
     * valida en el handler, que es quien conoce la fuente del costo; aqui solo se
     * descarta lo que nunca puede ser valido.
     */
    @DecimalMin(value = "0.00", message = "El precio no puede ser negativo")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El precio admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal price,
    /** Descripcion alterna solo para la factura; no altera el producto. */
    @Size(max = ColumnLimits.PRODUCT_NAME, message = "La descripcion para factura no puede exceder {max} caracteres")
    String invoiceDescription,
    /**
     * Instrucciones para quien cumple la solicitud (Control u Operador). Solo
     * las admite una linea que se pide al taller: una pieza especial sin unidad
     * elegida o un servicio sin trabajo ya ejecutado.
     */
    @Size(max = ColumnLimits.FULFILLMENT_INSTRUCTIONS,
            message = "Las instrucciones no pueden exceder {max} caracteres")
    String instructions,
    /**
     * Placa del vehiculo, para registrarla en una venta que todavia no la
     * tiene. Un servicio se hace sobre un vehiculo: solicitarlo exige que la
     * venta tenga placa, y esta es la forma de darsela en el mismo paso.
     */
    @Size(max = ColumnLimits.PLATE,
            message = "La placa no puede superar los {max} caracteres")
    String plate,
    /**
     * Pieza especial de una familia con colochos: si la pieza pedida los lleva.
     * Obligatorio cuando el producto no los fija; si los fija, debe coincidir.
     * Null en todo lo demas.
     */
    Boolean requestedCurls,
    /** Cuantos: obligatorio si los lleva (entero >= 1); nunca con "no". */
    @Min(value = 0, message = "La cantidad de colochos no puede ser negativa")
    Integer requestedCurlCount

) {

    public AddSaleDetailRequestDTO {
        invoiceDescription = invoiceDescription == null || invoiceDescription.isBlank()
                ? null
                : invoiceDescription.trim();
        instructions = instructions == null || instructions.isBlank()
                ? null
                : instructions.trim();
        plate = SalePlates.normalize(plate);
    }

    /** Sin decision de colochos: todo lo que no es una pieza especial con colochos. */
    public AddSaleDetailRequestDTO(
            UUID product,
            UUID item,
            UUID workResult,
            Integer quantity,
            BigDecimal price,
            String invoiceDescription,
            String instructions,
            String plate) {
        this(product, item, workResult, quantity, price, invoiceDescription, instructions, plate,
                null, null);
    }

    /** Sin placa: la venta ya la tiene o la linea no es un servicio. */
    public AddSaleDetailRequestDTO(
            UUID product,
            UUID item,
            UUID workResult,
            Integer quantity,
            BigDecimal price,
            String invoiceDescription,
            String instructions) {
        this(product, item, workResult, quantity, price, invoiceDescription, instructions, null);
    }

    /** La forma anterior a la comanda, sin instrucciones. */
    public AddSaleDetailRequestDTO(
            UUID product,
            UUID item,
            UUID workResult,
            Integer quantity,
            BigDecimal price,
            String invoiceDescription) {
        this(product, item, workResult, quantity, price, invoiceDescription, null, null);
    }

}
