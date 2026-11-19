package com.cns.spring_house.vehiclemovement.dto;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Cuerpo de {@code PATCH /v1/vehicle-movements/{id}/mechanic}.
 *
 * @param mechanicId operador que pasa a ser responsable de la visita.
 * @param expectedMechanicId mecanico que el cliente mostraba como actual al
 *        confirmar ({@code null} = la visita no tenia mecanico). Si ya no
 *        coincide con el vigente, la peticion responde 409 sin escribir:
 *        la confirmacion "antes / despues" que vio el usuario era falsa.
 * @param reason motivo del cambio, obligatorio en todo cambio real (se recorta;
 *        maximo {@value com.cns.spring_house.vehiclemovement.service.VehicleWorkService#REASON_MAX}
 *        caracteres). Un no-op lo ignora y no lo guarda.
 * @param unexpected cualquier otra propiedad. Se captura solo para
 *        rechazarla: inicio, fin, salida o taller nunca llegan del cliente.
 */
public record AssignMechanicRequestDTO(
        UUID mechanicId,
        UUID expectedMechanicId,
        String reason,
        @JsonAnySetter Map<String, Object> unexpected) {
}
