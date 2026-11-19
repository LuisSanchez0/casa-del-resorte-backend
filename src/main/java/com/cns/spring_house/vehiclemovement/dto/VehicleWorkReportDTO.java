package com.cns.spring_house.vehiclemovement.dto;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
/**
 * {@code dateBasis} repite la base que el servidor APLICO (ENTRY o FINISHED) a todas las cifras;
 * {@code serverTime} es el instante con el que se calculo el tiempo acumulado de los trabajos abiertos.
 * <p>
 * Definiciones (iguales en resumen, grupos y visitas; solo visitas FINALIZADAS en sumas y promedios):
 * <ul>
 *   <li>{@code totalSeconds}/{@code averageSeconds}: tiempo de TRABAJO = tiempo activo medido; para una
 *       visita sin registro de pausas (finalizada antes de V49) es su tiempo transcurrido historico.
 *       {@code legacyCompleted} cuenta esas visitas: si es mayor que cero la base es mixta.</li>
 *   <li>{@code measuredCompleted}/{@code measuredActiveSeconds}/{@code measuredAverageSeconds}: solo
 *       visitas con todos sus tramos activos registrados (activo puro, sin pausas).</li>
 *   <li>{@code pausedSeconds}: pausas registradas; {@code elapsedSeconds}: reloj de pared inicio→fin.
 *       Ninguno es productividad.</li>
 * </ul>
 */
public record VehicleWorkReportDTO(String dateBasis, Instant serverTime, Summary summary, List<MechanicSummary> mechanics,
        long mechanicCount, int mechanicPage, List<Job> jobs, long total, int page, int size) {
    public record Summary(long completed, long inProgress, long paused, long notStarted,
        BigDecimal totalSeconds, BigDecimal averageSeconds, long legacyCompleted,
        long measuredCompleted, BigDecimal measuredActiveSeconds, BigDecimal measuredAverageSeconds,
        BigDecimal pausedSeconds, BigDecimal elapsedSeconds) {}
    public record MechanicSummary(UUID mechanicId, String mechanicName, long completed, long inProgress, long paused,
        long notStarted, BigDecimal totalSeconds, BigDecimal averageSeconds, long legacyCompleted,
        long measuredCompleted, BigDecimal measuredActiveSeconds, BigDecimal measuredAverageSeconds,
        BigDecimal pausedSeconds, BigDecimal elapsedSeconds) {}
    /**
     * Una visita. {@code workState} NOT_STARTED/ACTIVE/PAUSED/FINISHED; {@code timingBasis} MEASURED,
     * LEGACY_PARTIAL o LEGACY_ELAPSED (ver {@code WorkTimingDTO}); {@code activeSeconds} es null cuando
     * no hay registro de tramos; {@code workSeconds} es la cifra que entra en sumas y promedios.
     */
    public record Job(UUID id, String plate, UUID workshopId, String workshopName, UUID mechanicId,
        String mechanicName, LocalDateTime entry, LocalDateTime exit, Instant workStartedAt,
        Instant workFinishedAt, String workState, String timingBasis, BigDecimal activeSeconds,
        BigDecimal pausedSeconds, BigDecimal elapsedSeconds, BigDecimal workSeconds) {}
}
