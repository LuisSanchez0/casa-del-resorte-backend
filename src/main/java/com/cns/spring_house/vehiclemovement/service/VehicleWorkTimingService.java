package com.cns.spring_house.vehiclemovement.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;
import org.springframework.stereotype.Service;

import com.cns.spring_house.vehiclemovement.dto.WorkTimingDTO;

import lombok.RequiredArgsConstructor;

/**
 * Tiempos de trabajo de una o varias visitas, en UNA consulta y con la formula
 * de {@link VehicleWorkTimeSql}. No autoriza ni filtra por taller: solo recibe
 * ids que el llamador ya resolvio dentro del taller del token.
 */
@Service
@RequiredArgsConstructor
public class VehicleWorkTimingService {

    private static final String SQL = "SELECT v.id, " + VehicleWorkTimeSql.COLUMNS
        + " FROM vehicle_movement v " + VehicleWorkTimeSql.JOIN_INTERVALS + " WHERE v.id = ANY(:ids)";

    private final NamedParameterJdbcTemplate jdbc;
    /** Reloj de la aplicacion ({@code TimeConfig}); las pruebas lo fijan para reproducir horarios exactos. */
    private final Clock clock;

    /** Instante del servidor con la precision de PostgreSQL: la unica fuente de inicio, pausa, reanudacion y fin. */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public WorkTimingDTO forVisit(UUID visit, Instant now) {
        return forVisits(java.util.List.of(visit), now).get(visit);
    }

    public Map<UUID, WorkTimingDTO> forVisits(Collection<UUID> visits, Instant now) {
        Map<UUID, WorkTimingDTO> result = new HashMap<>();
        if (visits.isEmpty()) return result;
        UUID[] ids = visits.toArray(UUID[]::new);
        // Un arreglo uuid[] y no una lista IN expandida: el numero de visitas no limita la consulta.
        var params = new MapSqlParameterSource("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .addValue("ids", new AbstractSqlTypeValue() {
                @Override
                protected Object createTypeValue(Connection connection, int sqlType, String typeName) throws SQLException {
                    return connection.createArrayOf("uuid", ids);
                }
            });
        jdbc.query(SQL, params, (ResultSet r) -> {
            result.put(r.getObject("id", UUID.class), read(r, now));
        });
        return result;
    }

    static WorkTimingDTO read(ResultSet r, Instant now) throws SQLException {
        return new WorkTimingDTO(r.getString("work_state"), r.getString("timing_basis"),
            r.getBigDecimal("active_seconds"), r.getBigDecimal("paused_seconds"),
            r.getBigDecimal("elapsed_seconds"), r.getBigDecimal("work_seconds"),
            instant(r.getTimestamp("active_since")), instant(r.getTimestamp("paused_since")), now);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
