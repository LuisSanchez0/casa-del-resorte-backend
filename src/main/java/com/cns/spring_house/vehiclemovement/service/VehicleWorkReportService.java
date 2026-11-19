package com.cns.spring_house.vehiclemovement.service;
import java.time.*;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.jdbc.core.namedparam.*;
import com.cns.spring_house.shared.config.time.TimeConfig;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.shared.exception.*;
import com.cns.spring_house.vehiclemovement.dto.VehicleWorkReportDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleWorkReportDTO.*;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class VehicleWorkReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final VehicleWorkTimingService timings;
    /** Visitas del reporte sin tiempos: basta para contar grupos. */
    private static final String PLAIN_FROM = """
       FROM vehicle_movement v JOIN workshop w ON w.id=v.workshop_id
       LEFT JOIN user_account u ON u.id=v.mechanic_user_id LEFT JOIN employee e ON e.id=u.employee_id
       """;
    /** Las mismas visitas con sus tramos agregados (una fila por visita, sin N+1). */
    private static final String FROM = PLAIN_FROM + VehicleWorkTimeSql.JOIN_INTERVALS;
    private static final String NAME = "coalesce(e.first_name || ' ' || e.last_name, 'Sin mecánico asignado')";
    private static final String FINISHED = "v.work_finished_at IS NOT NULL";
    /**
     * COUNT, SUM y AVG salen de la MISMA expresion por visita ({@link VehicleWorkTimeSql#WORK}) y del
     * mismo filtro: solo visitas finalizadas. Una visita abierta o pausada nunca entra en un promedio.
     */
    private static final String COUNTS = "count(v.work_finished_at) AS completed,"
       + " count(*) FILTER (WHERE v.work_started_at IS NOT NULL AND v.work_finished_at IS NULL) AS in_progress,"
       + " count(*) FILTER (WHERE v.work_started_at IS NOT NULL AND v.work_finished_at IS NULL"
       + "   AND wi.interval_count > 0 AND wi.open_since IS NULL) AS paused,"
       + " count(*) FILTER (WHERE v.work_started_at IS NULL) AS not_started,"
       + " coalesce(sum(" + VehicleWorkTimeSql.WORK + ") FILTER (WHERE " + FINISHED + "), 0) AS total_seconds,"
       + " avg(" + VehicleWorkTimeSql.WORK + ") FILTER (WHERE " + FINISHED + ") AS average_seconds,"
       + " count(*) FILTER (WHERE " + FINISHED + " AND NOT (" + VehicleWorkTimeSql.MEASURED_FINISHED + ")) AS legacy_completed,"
       + " count(*) FILTER (WHERE " + VehicleWorkTimeSql.MEASURED_FINISHED + ") AS measured_completed,"
       + " coalesce(sum(" + VehicleWorkTimeSql.ACTIVE + ") FILTER (WHERE " + VehicleWorkTimeSql.MEASURED_FINISHED
       + "), 0) AS measured_active_seconds,"
       + " avg(" + VehicleWorkTimeSql.ACTIVE + ") FILTER (WHERE " + VehicleWorkTimeSql.MEASURED_FINISHED
       + ") AS measured_average_seconds,"
       + " coalesce(sum(" + VehicleWorkTimeSql.PAUSED + ") FILTER (WHERE " + FINISHED + "), 0) AS paused_seconds,"
       + " coalesce(sum(" + VehicleWorkTimeSql.ELAPSED + ") FILTER (WHERE " + FINISHED + "), 0) AS elapsed_seconds ";
    private static final String JOB = "v.id, v.plate, v.workshop_id, w.name AS workshop_name, v.mechanic_user_id, "
       + NAME + " AS mechanic_name, v.entry, v.exit, v.work_started_at, v.work_finished_at, "
       + VehicleWorkTimeSql.COLUMNS + " ";

    /**
     * {@code dateBasis} decide que evento acota el periodo; las dos preguntas
     * nunca se mezclan dentro de una misma respuesta:
     * <ul>
     *   <li>{@code ENTRY} (predeterminado): visitas cuyo INGRESO cae en las
     *       fechas. Incluye pendientes, en curso y finalizadas de esa cohorte.</li>
     *   <li>{@code FINISHED}: visitas cuyo trabajo TERMINO en las fechas. Una
     *       visita sin {@code work_finished_at} no tiene fecha de fin y nunca
     *       entra, ni siquiera sin fechas.</li>
     * </ul>
     * El mismo {@code where} alimenta resumen, conteos, grupos y pagina de
     * visitas, dentro de una unica instantanea REPEATABLE READ.
     */
    public VehicleWorkReportDTO report(LocalDate from, LocalDate to, UUID mechanicId, String state,
            String dateBasis, int page, int size, int mechanicPage) {
        if (page < 0 || page > 100000 || mechanicPage < 0 || mechanicPage > 100000 || size < 1 || size > 100)
            throw new ValidationException("La página debe ser válida y el tamaño estar entre 1 y 100");
        if (from != null && to != null && from.isAfter(to))
            throw new ValidationException("La fecha inicial no puede ser posterior a la final");
        if (!Set.of("ALL", "NOT_STARTED", "IN_PROGRESS", "FINISHED").contains(state))
            throw new ValidationException("Estado de trabajo inválido");
        if (!Set.of("ENTRY", "FINISHED").contains(dateBasis))
            throw new ValidationException("Base de fecha inválida: usa ENTRY (ingreso) o FINISHED (finalización)");
        boolean byFinish = "FINISHED".equals(dateBasis);
        if (byFinish && !Set.of("ALL", "FINISHED").contains(state))
            throw new ValidationException("Por fecha de finalización solo hay trabajos finalizados. "
                + "Elige el estado «Todos» o «Trabajo finalizado».");
        Instant now = timings.now();
        var p = new MapSqlParameterSource("workshop", SecurityUtils.getWorkshopId())
            .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        String where = " WHERE v.workshop_id=:workshop";
        if (byFinish) {
            // work_finished_at is TIMESTAMPTZ: compare against the instants where the selected
            // Guatemala days begin, never against a UTC or server-dependent date cast.
            where += " AND v.work_finished_at IS NOT NULL";
            if (from != null) { where += " AND v.work_finished_at>=:from"; p.addValue("from", businessDayStart(from)); }
            if (to != null) { where += " AND v.work_finished_at<:until"; p.addValue("until", businessDayStart(to.plusDays(1))); }
        } else {
            // Existing entry is Guatemala LocalDateTime. Never reinterpret legacy values as UTC.
            if (from != null) { where += " AND v.entry>=:from"; p.addValue("from", from.atStartOfDay()); }
            if (to != null) { where += " AND v.entry<:until"; p.addValue("until", to.plusDays(1).atStartOfDay()); }
        }
        if (mechanicId != null) { where += " AND v.mechanic_user_id=:mechanic"; p.addValue("mechanic", mechanicId); }
        where += switch(state) {
            case "FINISHED" -> " AND v.work_finished_at IS NOT NULL";
            case "IN_PROGRESS" -> " AND v.work_started_at IS NOT NULL AND v.work_finished_at IS NULL";
            case "NOT_STARTED" -> " AND v.work_started_at IS NULL";
            default -> "";
        };
        var summary = jdbc.queryForObject("SELECT " + COUNTS + FROM + where, p, (r,n) -> new Summary(
            r.getLong("completed"), r.getLong("in_progress"), r.getLong("paused"), r.getLong("not_started"),
            r.getBigDecimal("total_seconds"), r.getBigDecimal("average_seconds"), r.getLong("legacy_completed"),
            r.getLong("measured_completed"), r.getBigDecimal("measured_active_seconds"),
            r.getBigDecimal("measured_average_seconds"), r.getBigDecimal("paused_seconds"),
            r.getBigDecimal("elapsed_seconds")));
        long total = summary.completed() + summary.inProgress() + summary.notStarted();
        String group = " GROUP BY v.mechanic_user_id, e.first_name, e.last_name";
        long mechanicCount = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 " + PLAIN_FROM + where + group + ") g",
            p, Long.class);
        p.addValue("size", size).addValue("offset", page * size).addValue("mechanicOffset", mechanicPage * size);
        var mechanics = jdbc.query("SELECT v.mechanic_user_id, " + NAME + " AS mechanic_name, " + COUNTS + FROM + where + group
            + " ORDER BY completed DESC, mechanic_name, v.mechanic_user_id NULLS LAST LIMIT :size OFFSET :mechanicOffset", p,
            (r,n) -> new MechanicSummary(r.getObject("mechanic_user_id", UUID.class), r.getString("mechanic_name"),
                r.getLong("completed"), r.getLong("in_progress"), r.getLong("paused"), r.getLong("not_started"),
                r.getBigDecimal("total_seconds"), r.getBigDecimal("average_seconds"), r.getLong("legacy_completed"),
                r.getLong("measured_completed"), r.getBigDecimal("measured_active_seconds"),
                r.getBigDecimal("measured_average_seconds"), r.getBigDecimal("paused_seconds"),
                r.getBigDecimal("elapsed_seconds")));
        String order = byFinish ? " ORDER BY v.work_finished_at DESC, v.id DESC" : " ORDER BY v.entry DESC, v.id DESC";
        var jobs = jdbc.query("SELECT " + JOB + FROM + where + order + " LIMIT :size OFFSET :offset", p, (r,n) -> job(r));
        return new VehicleWorkReportDTO(dateBasis, now, summary, mechanics, mechanicCount, mechanicPage, jobs, total, page, size);
    }
    public Job detail(UUID id) {
        return jdbc.query("SELECT " + JOB + FROM + " WHERE v.id=:id AND v.workshop_id=:workshop",
            new MapSqlParameterSource("id", id).addValue("workshop", SecurityUtils.getWorkshopId())
                .addValue("now", OffsetDateTime.ofInstant(timings.now(), ZoneOffset.UTC)), (r,n) -> job(r))
            .stream().findFirst().orElseThrow(() -> new EntityNotFoundException("No existe el registro especificado"));
    }
    private Job job(ResultSet r) throws SQLException {
        return new Job(r.getObject("id", UUID.class), r.getString("plate"), r.getObject("workshop_id", UUID.class),
            r.getString("workshop_name"), r.getObject("mechanic_user_id", UUID.class), r.getString("mechanic_name"),
            r.getObject("entry", LocalDateTime.class), r.getObject("exit", LocalDateTime.class),
            instant(r, "work_started_at"), instant(r, "work_finished_at"), r.getString("work_state"),
            r.getString("timing_basis"), r.getBigDecimal("active_seconds"), r.getBigDecimal("paused_seconds"),
            r.getBigDecimal("elapsed_seconds"), r.getBigDecimal("work_seconds"));
    }
    private static OffsetDateTime businessDayStart(LocalDate day) {
        return day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toOffsetDateTime();
    }
    private Instant instant(ResultSet r, String name) throws SQLException {
        var value = r.getTimestamp(name); return value == null ? null : value.toInstant();
    }
}
