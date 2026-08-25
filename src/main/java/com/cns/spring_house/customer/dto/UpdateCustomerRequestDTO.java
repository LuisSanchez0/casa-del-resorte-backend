package com.cns.spring_house.customer.dto;

import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.shared.validator.notblank.NotBlankIfPresent;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequestDTO(

    /**
     * Ambos campos son opcionales: es un PATCH y el mapper ignora los nulos, de
     * modo que se puede cambiar solo el nombre o solo el nit. Lo que llegue, eso
     * si, tiene que respetar los mismos limites que en el alta.
     */
    @Pattern(regexp = "^\\d{8,13}$", message = "El nit debe contener de 8 a 13 digitos")
    @Size(max = ColumnLimits.CUSTOMER_NIT,
            message = "El nit no puede superar los {max} caracteres")
    String nit,
    /**
     * Omitirlo deja el nombre como esta; enviarlo en blanco NO. Sin
     * {@code @NotBlankIfPresent} un PATCH con espacios se guardaba tal cual,
     * porque {@code @Size} mide longitud y no contenido, y el cliente acababa
     * sin nombre con una respuesta 200.
     */
    @NotBlankIfPresent(message = "El nombre no puede estar en blanco")
    @Size(max = ColumnLimits.CUSTOMER_NAME,
            message = "El nombre no puede superar los {max} caracteres")
    String name

) {
    
}
