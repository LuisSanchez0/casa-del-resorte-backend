package com.cns.spring_house.sale.dto;

/**
 * Una caracteristica en la que la pieza de referencia no es igual a la pedida.
 * `reference` es null si la referencia no la declara.
 */
public record AttributeDifferenceDTO(
    String attribute,
    Object requested,
    Object reference
) {
}
