package com.cns.spring_house.customer.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequestDTO(

    /**
     * El patron ya acota la longitud a 13 digitos, que es justo
     * ColumnLimits.CUSTOMER_NIT; el @Size se declara igual para que un cambio de
     * columna que no toque el patron no pase inadvertido.
     */
    @NotBlank(message = "El nit es obligatorio")
    @Pattern(regexp = "^\\d{8,13}$", message = "El nit debe contener de 8 a 13 digitos")
    @Size(max = ColumnLimits.CUSTOMER_NIT,
            message = "El nit no puede superar los {max} caracteres")
    String nit,
    /**
     * Sin este tope un nombre largo llegaba hasta PostgreSQL y volvia como un 500
     * ("value too long for type character varying(75)") por lo que en realidad es
     * un error de validacion del cliente.
     */
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String name

) {

}
