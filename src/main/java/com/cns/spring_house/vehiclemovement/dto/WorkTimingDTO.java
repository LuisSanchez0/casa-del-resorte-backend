package com.cns.spring_house.vehiclemovement.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Estado y tiempos del trabajo de UNA visita, calculados por el servidor en
 * {@code serverTime} con la misma formula que el reporte de Admin
 * ({@code VehicleWorkTimeSql}). El navegador solo anima el contador entre
 * lecturas; nunca es la fuente de la duracion.
 *
 * @param state NOT_STARTED, ACTIVE, PAUSED o FINISHED.
 * @param basis como leer los segundos: MEASURED (todos los tramos activos
 *        registrados), LEGACY_PARTIAL (incluye un tramo previo a V49, sin
 *        registro de pausas) o LEGACY_ELAPSED (visita sin tramos: solo hay
 *        tiempo transcurrido entre inicio y fin/ahora). {@code null} sin iniciar.
 * @param activeSeconds suma de tramos activos a {@code serverTime}; congelado
 *        en pausa y al finalizar. {@code null} sin iniciar o con LEGACY_ELAPSED.
 * @param pausedSeconds transcurrido menos activo; {@code null} si no hay tramos.
 * @param elapsedSeconds reloj de pared desde el primer inicio hasta el fin (o
 *        {@code serverTime}); NO es tiempo de trabajo.
 * @param workSeconds la cifra de trabajo de la visita: el tiempo activo, o el
 *        transcurrido historico cuando no hay registro de pausas (ver basis).
 * @param activeSince inicio del tramo abierto (solo ACTIVE con tramos).
 * @param pausedSince inicio de la pausa en curso (solo PAUSED).
 */
public record WorkTimingDTO(
        String state,
        String basis,
        BigDecimal activeSeconds,
        BigDecimal pausedSeconds,
        BigDecimal elapsedSeconds,
        BigDecimal workSeconds,
        Instant activeSince,
        Instant pausedSince,
        Instant serverTime) {
}
