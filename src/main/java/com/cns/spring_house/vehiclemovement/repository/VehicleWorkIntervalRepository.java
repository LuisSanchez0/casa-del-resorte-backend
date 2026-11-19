package com.cns.spring_house.vehiclemovement.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Escrituras de los tramos ACTIVOS (V49). Solo se llama con la visita ya
 * bloqueada {@code FOR UPDATE} por {@code VehicleWorkService}, en su misma
 * transaccion; ademas, los indices unicos parciales y el trigger de V49
 * rechazan un segundo tramo abierto, un solapamiento o un tramo fuera de un
 * trabajo en curso. No existe borrado ni otra modificacion que cerrar.
 */
@Repository
@RequiredArgsConstructor
public class VehicleWorkIntervalRepository {

    public enum Origin { START, RESUME, LEGACY_CARRYOVER }

    /** Tramos de una visita al momento de la lectura (bajo el bloqueo de la visita). */
    public record State(long count, Instant openSince, Instant lastEndedAt) {
        public boolean open() {
            return openSince != null;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public State state(UUID visit) {
        return jdbc.queryForObject("""
            SELECT count(*) AS n, max(started_at) FILTER (WHERE ended_at IS NULL) AS open_since,
                   max(ended_at) AS last_ended_at
              FROM vehicle_work_interval WHERE vehicle_movement_id = :visit
            """, new MapSqlParameterSource("visit", visit),
            (r, n) -> new State(r.getLong("n"), instant(r.getTimestamp("open_since")),
                instant(r.getTimestamp("last_ended_at"))));
    }

    /** Abre un tramo (inicio o reanudacion). */
    public void open(UUID visit, Origin origin, Instant at, UUID by) {
        jdbc.update("""
            INSERT INTO vehicle_work_interval(id, vehicle_movement_id, origin, started_at, started_by_user_id)
            VALUES (:id, :visit, :origin, :at, :by)
            """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("visit", visit)
                .addValue("origin", origin.name()).addValue("at", Timestamp.from(at)).addValue("by", by));
    }

    /** Cierra el unico tramo abierto; devuelve cuantas filas cerro (0 o 1). */
    public int closeOpen(UUID visit, Instant at, UUID by) {
        return jdbc.update("""
            UPDATE vehicle_work_interval SET ended_at = :at, ended_by_user_id = :by
             WHERE vehicle_movement_id = :visit AND ended_at IS NULL
            """, new MapSqlParameterSource("visit", visit).addValue("at", Timestamp.from(at)).addValue("by", by));
    }

    /**
     * Traspaso de una visita que ya estaba en trabajo antes de V49: un tramo ya
     * cerrado desde su inicio real hasta esta primera pausa o fin.
     */
    public void insertLegacyCarryover(UUID visit, Instant startedAt, UUID startedBy, Instant endedAt, UUID endedBy) {
        jdbc.update("""
            INSERT INTO vehicle_work_interval(id, vehicle_movement_id, origin, started_at, ended_at,
                                              started_by_user_id, ended_by_user_id)
            VALUES (:id, :visit, 'LEGACY_CARRYOVER', :started, :ended, :startedBy, :endedBy)
            """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("visit", visit)
                .addValue("started", Timestamp.from(startedAt)).addValue("ended", Timestamp.from(endedAt))
                .addValue("startedBy", startedBy).addValue("endedBy", endedBy));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
