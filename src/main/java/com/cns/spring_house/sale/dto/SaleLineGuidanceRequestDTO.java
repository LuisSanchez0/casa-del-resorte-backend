package com.cns.spring_house.sale.dto;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Size;

/**
 * La linea sobre la que se pide guia: una pieza del catalogo (`product`) o una
 * especificacion que quiza todavia no existe (`family` + `model` +
 * `attributes`, igual que en "Solicitar con estas caracteristicas"). Una de
 * las dos formas, nunca las dos.
 */
public record SaleLineGuidanceRequestDTO(
    UUID product,
    UUID family,
    UUID model,
    @Size(max = 50, message = "Demasiadas caracteristicas")
    Map<String, Object> attributes
) {
}
