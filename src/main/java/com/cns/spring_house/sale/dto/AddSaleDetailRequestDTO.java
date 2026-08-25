package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
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
    String invoiceDescription

) {

    public AddSaleDetailRequestDTO {
        invoiceDescription = invoiceDescription == null || invoiceDescription.isBlank()
                ? null
                : invoiceDescription.trim();
    }

}
