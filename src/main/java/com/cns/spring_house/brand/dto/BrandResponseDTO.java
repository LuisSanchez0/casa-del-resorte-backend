package com.cns.spring_house.brand.dto;

import java.util.UUID;

/**
 * `deleted` es SOLO de lectura: los listados y el detalle nunca devuelven una
 * marca eliminada, pero un producto historico sigue trayendo la suya dentro de
 * su modelo, y quien lo muestra necesita saber que ya no se puede elegir. No
 * existe en ningun DTO de entrada.
 */
public record BrandResponseDTO(

        UUID id,
        String name,
        String logoKey,
        boolean deleted

) { }
