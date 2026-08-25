package com.cns.spring_house.vehiclemovement.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

/**
 * Respuesta de un movimiento de vehiculo.
 *
 * {@code phone} es el telefono capturado en ESA entrada, no el ultimo conocido
 * de la placa: el historial de cada visita muestra su propio valor y una visita
 * posterior con otro telefono no lo cambia.
 *
 * Llega en null en dos situaciones distintas que el cliente no necesita
 * distinguir, porque las dos se muestran igual ("No registrado"):
 *
 * <ul>
 *   <li>movimientos anteriores a V31, que no tienen telefono demostrable;</li>
 *   <li>peticiones de un rol que no es POLICE ni ADMIN, para las que el servicio
 *       lo omite deliberadamente.</li>
 * </ul>
 *
 * Lo segundo importa: {@code GET /v1/vehicle-movements} NO es un endpoint solo
 * policial. Vendedor lo consulta tambien para sugerir placas al facturar. Anadir
 * el telefono al DTO sin mas habria empezado a mandar datos de contacto en cada
 * respuesta de ese autocompletado. Quien decide es
 * {@code VehicleMovementServiceImpl}, no este record: ver alli.
 */
public record VehicleMovementResponseDTO(

        UUID id,
        String plate,
        WorkshopResponseDTO workshop,
        LocalDateTime entry,
        LocalDateTime exit,
        String phone,
        String notes,
        List<VehicleMovementPhotoResponseDTO> photos

) {

}
