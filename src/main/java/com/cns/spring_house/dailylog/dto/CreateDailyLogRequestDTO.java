package com.cns.spring_house.dailylog.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Alta de un registro historico a partir de una venta.
 *
 * Ni el empleado ni el taller viajan en la peticion: los resuelve el servicio
 * desde la identidad autenticada, igual que el resto del proyecto. Enviarlos
 * permitiria firmar un registro a nombre de otra persona.
 *
 * Las listas son opcionales por separado —puede haber solo productos, solo
 * servicios o ambos— pero el servicio exige al menos una linea entre las dos:
 * un registro sin ninguna no describe nada.
 */
public record CreateDailyLogRequestDTO(

        @NotNull(message = "La venta es obligatoria")
        UUID sale,
        @Valid
        List<DailyLogProductRequestDTO> products,
        @Valid
        List<DailyLogServiceRequestDTO> services,
        String description

) {
}
