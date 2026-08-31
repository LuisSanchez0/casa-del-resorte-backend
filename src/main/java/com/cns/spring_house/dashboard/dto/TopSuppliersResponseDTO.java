package com.cns.spring_house.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Respuesta del ranking de proveedores.
 *
 * {@code from} y {@code to} declaran el periodo que se midio y son NULL cuando
 * el ranking es historico. No es un detalle de implementacion: mezclar en la
 * misma pantalla graficas de un periodo con un ranking de toda la historia sin
 * decirlo es la forma mas facil de que alguien lea las dos cifras como si
 * hablaran del mismo tiempo. La interfaz rotula la tarjeta con lo que venga
 * aqui.
 */
public record TopSuppliersResponseDTO(

        LocalDate from,
        LocalDate to,
        List<SupplierPerformanceDTO> suppliers

) { }
