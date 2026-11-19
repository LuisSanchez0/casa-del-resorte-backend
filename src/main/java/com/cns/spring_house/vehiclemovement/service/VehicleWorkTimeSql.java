package com.cns.spring_house.vehiclemovement.service;

/**
 * La UNICA formula del tiempo de trabajo, compartida por las tarjetas de
 * Policia ({@link VehicleWorkTimingService}) y el reporte de Admin
 * ({@link VehicleWorkReportService}). Cada consulta la aplica a la visita
 * {@code v} con el parametro {@code :now} (instante del servidor de esa
 * lectura); asi una visita muestra la misma cifra en ambos lados.
 * <pre>
 * activo      = SUM(fin - inicio) de sus tramos, usando :now solo para el tramo abierto
 * transcurrido = (work_finished_at o :now) - work_started_at
 * pausado     = transcurrido - activo            (solo si hay tramos)
 * trabajo     = activo, o el transcurrido historico si la visita no tiene tramos
 * </pre>
 * Una visita sin tramos (iniciada antes de V49) no tiene tiempo activo medido:
 * su base es LEGACY_ELAPSED y nunca se presenta como activo.
 */
final class VehicleWorkTimeSql {

    private VehicleWorkTimeSql() {
    }

    /** Tramos agregados de la visita {@code v} (alias {@code wi}); una fila por visita, sin N+1. */
    static final String JOIN_INTERVALS = """
         LEFT JOIN LATERAL (
           SELECT count(*) AS interval_count,
                  coalesce(bool_or(i.origin = 'LEGACY_CARRYOVER'), false) AS legacy_carryover,
                  coalesce(sum(extract(epoch FROM coalesce(i.ended_at, :now)) - extract(epoch FROM i.started_at)), 0)
                      AS interval_seconds,
                  max(i.started_at) FILTER (WHERE i.ended_at IS NULL) AS open_since,
                  max(i.ended_at) AS last_ended_at
             FROM vehicle_work_interval i
            WHERE i.vehicle_movement_id = v.id) wi ON true
         """;

    static final String STATE = "CASE WHEN v.work_started_at IS NULL THEN 'NOT_STARTED'"
        + " WHEN v.work_finished_at IS NOT NULL THEN 'FINISHED'"
        + " WHEN wi.open_since IS NOT NULL OR wi.interval_count = 0 THEN 'ACTIVE' ELSE 'PAUSED' END";

    static final String BASIS = "CASE WHEN v.work_started_at IS NULL THEN NULL"
        + " WHEN wi.interval_count = 0 THEN 'LEGACY_ELAPSED'"
        + " WHEN wi.legacy_carryover THEN 'LEGACY_PARTIAL' ELSE 'MEASURED' END";

    static final String ELAPSED = "(extract(epoch FROM coalesce(v.work_finished_at, :now))"
        + " - extract(epoch FROM v.work_started_at))";

    static final String ACTIVE = "(CASE WHEN wi.interval_count > 0 THEN wi.interval_seconds END)";

    static final String PAUSED = "(CASE WHEN wi.interval_count > 0 THEN " + ELAPSED + " - wi.interval_seconds END)";

    static final String WORK = "(CASE WHEN v.work_started_at IS NULL THEN NULL"
        + " WHEN wi.interval_count > 0 THEN wi.interval_seconds ELSE " + ELAPSED + " END)";

    /** Visita finalizada cuyo tiempo activo esta medido completo (todos sus tramos posteriores a V49). */
    static final String MEASURED_FINISHED = "v.work_finished_at IS NOT NULL AND wi.interval_count > 0"
        + " AND NOT wi.legacy_carryover";

    /** Columnas por visita, para las tarjetas y las filas del reporte. */
    static final String COLUMNS = STATE + " AS work_state, " + BASIS + " AS timing_basis, "
        + ACTIVE + " AS active_seconds, " + PAUSED + " AS paused_seconds, " + ELAPSED + " AS elapsed_seconds, "
        + WORK + " AS work_seconds, "
        + "CASE WHEN v.work_finished_at IS NULL THEN wi.open_since END AS active_since, "
        + "CASE WHEN v.work_finished_at IS NULL AND wi.open_since IS NULL AND wi.interval_count > 0"
        + " THEN wi.last_ended_at END AS paused_since";
}
