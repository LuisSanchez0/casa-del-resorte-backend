package com.cns.spring_house.vehiclemovement.dto;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Cuerpo de {@code POST /v1/vehicle-movements/{id}/start-work}: el mecanico
 * responsable se elige AL INICIAR el trabajo, no al registrar el ingreso.
 *
 * @param mechanicId operador del taller que queda como responsable; obligatorio.
 * @param expectedMechanicId mecanico que el dialogo mostraba como asignado
 *        ({@code null} = la visita no tenia). Si ya no coincide con el vigente,
 *        la peticion responde 409 sin escribir nada.
 * @param reason motivo, obligatorio SOLO si la visita ya tenia otro mecanico
 *        (asignacion previa de una visita historica): ese cambio es una
 *        REASSIGNMENT de V47/V48. La primera asignacion no lleva motivo y uno
 *        enviado se ignora.
 * @param unexpected cualquier otra propiedad; se captura solo para rechazarla.
 *        Fechas, duraciones y taller nunca llegan del cliente.
 */
public record StartWorkRequestDTO(
        UUID mechanicId,
        UUID expectedMechanicId,
        String reason,
        @JsonAnySetter Map<String, Object> unexpected) {
}
