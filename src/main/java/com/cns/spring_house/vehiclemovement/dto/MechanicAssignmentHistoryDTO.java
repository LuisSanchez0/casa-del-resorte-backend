package com.cns.spring_house.vehiclemovement.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pagina de la bitacora de asignaciones de UNA visita, mas recientes primero.
 * <p>
 * {@code Person.nameAtEvent} es el nombre guardado AL MOMENTO del evento (V48);
 * {@code Person.name} es el nombre ACTUAL de la cuenta, solo como referencia. El
 * id es la identidad canonica. {@code namesRecordedAtEvent} es false en los
 * eventos anteriores a V48: sus nombres historicos y su motivo no existen y no
 * se reconstruyen. {@code reason} solo existe en reasignaciones posteriores a V48.
 */
public record MechanicAssignmentHistoryDTO(UUID visitId, List<Event> events, long total, int page, int size) {
    public record Person(UUID id, String name, String nameAtEvent) {}
    public record Event(UUID id, String eventType, Person previousMechanic, Person newMechanic,
            Person changedBy, Instant changedAt, String reason, boolean namesRecordedAtEvent) {}
}
