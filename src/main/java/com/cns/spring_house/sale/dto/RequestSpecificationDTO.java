package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "Solicitar con estas caracteristicas": una pieza especial que el cliente
 * quiere y que todavia no existe como producto del catalogo.
 *
 * El vendedor describe el RESULTADO —familia, modelo y caracteristicas—; nunca
 * la pieza de origen. Las caracteristicas se validan con las mismas reglas que
 * el alta de un producto, asi que lo que aqui se acepta es algo que el
 * catalogo admitiria igual.
 */
public record RequestSpecificationDTO(

    @NotNull(message = "La familia es obligatoria")
    UUID family,
    /** Obligatorio si la familia exige modelo. */
    UUID model,
    @NotEmpty(message = "Indique las caracteristicas de la pieza")
    Map<String, Object> attributes,
    @Min(value = 1, message = "La cantidad debe ser al menos {value}")
    @Max(value = ColumnLimits.FULFILLMENT_MAX_UNITS,
            message = "La cantidad maxima por solicitud es {value}")
    Integer quantity,
    @NotNull(message = "El precio de venta es obligatorio")
    @DecimalMin(value = "0.01", message = "El precio debe ser mayor que cero")
    @Digits(integer = ColumnLimits.MONEY_INTEGER_DIGITS,
            fraction = ColumnLimits.MONEY_FRACTION_DIGITS,
            message = "El precio admite hasta {integer} enteros y {fraction} decimales")
    BigDecimal price,
    @Size(max = ColumnLimits.PRODUCT_NAME,
            message = "La descripcion para factura no puede exceder {max} caracteres")
    String invoiceDescription,
    @Size(max = ColumnLimits.FULFILLMENT_INSTRUCTIONS,
            message = "Las instrucciones no pueden exceder {max} caracteres")
    String instructions,
    /**
     * Si la pieza lleva colochos, cuando la familia los maneja. Obligatorio si
     * las caracteristicas no fijan `cantidadColochos`; si la fijan, debe
     * coincidir.
     */
    Boolean requestedCurls,
    /**
     * Cuantos, si los lleva y no van ya en las caracteristicas (entero >= 1).
     * "Si" exige la cantidad, aqui o en `cantidadColochos`.
     */
    @Min(value = 0, message = "La cantidad de colochos no puede ser negativa")
    Integer requestedCurlCount

) {

    /** Sin decision de colochos: la familia no los maneja o los fijan las caracteristicas. */
    public RequestSpecificationDTO(
            UUID family,
            UUID model,
            Map<String, Object> attributes,
            Integer quantity,
            BigDecimal price,
            String invoiceDescription,
            String instructions) {
        this(family, model, attributes, quantity, price, invoiceDescription, instructions, null, null);
    }

    public RequestSpecificationDTO {
        invoiceDescription = invoiceDescription == null || invoiceDescription.isBlank()
                ? null
                : invoiceDescription.trim();
        instructions = instructions == null || instructions.isBlank()
                ? null
                : instructions.trim();
    }

}
