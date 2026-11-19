package com.cns.spring_house.vehiclemovement.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.vehiclemovement.dto.MechanicAssignmentHistoryDTO;
import com.cns.spring_house.vehiclemovement.dto.MechanicAssignmentHistoryDTO.Event;
import com.cns.spring_house.vehiclemovement.dto.MechanicAssignmentHistoryDTO.Person;

import lombok.RequiredArgsConstructor;

/**
 * Lectura de la bitacora de asignaciones (V47) de una visita del taller del
 * token. Policia y Admin la consultan con el mismo alcance que ya tienen sobre
 * la visita: una visita de otro taller responde igual que una inexistente (404).
 * <p>
 * Devuelve los nombres guardados al momento del evento (V48) junto a los
 * actuales. Una sola consulta con los nombres resueltos por JOIN (sin N+1), paginada y
 * acotada; conteo y pagina comparten una instantanea REPEATABLE READ.
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('POLICE','ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class VehicleMechanicHistoryService {
    public static final int MAX_SIZE = 50;
    private final NamedParameterJdbcTemplate jdbc;

    private static String name(String alias) {
        return alias + ".first_name || ' ' || " + alias + ".last_name";
    }

    private static final String PAGE = "SELECT a.id, a.event_type, a.changed_at, a.reassignment_reason,"
        + " a.previous_mechanic_user_id, " + name("pe") + " AS previous_name, a.previous_mechanic_name_at_event,"
        + " a.new_mechanic_user_id, " + name("ne") + " AS new_name, a.new_mechanic_name_at_event,"
        + " a.changed_by_user_id, " + name("ce") + " AS actor_name, a.actor_name_at_event"
        + " FROM vehicle_mechanic_assignment_event a"
        + " LEFT JOIN user_account pu ON pu.id = a.previous_mechanic_user_id LEFT JOIN employee pe ON pe.id = pu.employee_id"
        + " LEFT JOIN user_account nu ON nu.id = a.new_mechanic_user_id LEFT JOIN employee ne ON ne.id = nu.employee_id"
        + " LEFT JOIN user_account cu ON cu.id = a.changed_by_user_id LEFT JOIN employee ce ON ce.id = cu.employee_id"
        + " WHERE a.vehicle_movement_id = :visit"
        + " ORDER BY a.changed_at DESC, a.id DESC LIMIT :size OFFSET :offset";

    public MechanicAssignmentHistoryDTO history(UUID visitId, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > MAX_SIZE)
            throw new ValidationException("La página debe ser válida y el tamaño estar entre 1 y " + MAX_SIZE);
        var params = new MapSqlParameterSource("visit", visitId).addValue("workshop", SecurityUtils.getWorkshopId());
        Long owned = jdbc.queryForObject(
            "SELECT count(*) FROM vehicle_movement WHERE id = :visit AND workshop_id = :workshop", params, Long.class);
        if (owned == null || owned == 0) throw new EntityNotFoundException("No existe el registro especificado");
        Long total = jdbc.queryForObject(
            "SELECT count(*) FROM vehicle_mechanic_assignment_event WHERE vehicle_movement_id = :visit", params, Long.class);
        params.addValue("size", size).addValue("offset", page * size);
        var events = jdbc.query(PAGE, params, (r, n) -> event(r));
        return new MechanicAssignmentHistoryDTO(visitId, events, total == null ? 0 : total, page, size);
    }

    private Event event(ResultSet r) throws SQLException {
        return new Event(r.getObject("id", UUID.class), r.getString("event_type"),
            person(r, "previous_mechanic_user_id", "previous_name", "previous_mechanic_name_at_event"),
            person(r, "new_mechanic_user_id", "new_name", "new_mechanic_name_at_event"),
            person(r, "changed_by_user_id", "actor_name", "actor_name_at_event"),
            r.getTimestamp("changed_at").toInstant(), r.getString("reassignment_reason"),
            r.getString("new_mechanic_name_at_event") != null);
    }

    private Person person(ResultSet r, String idColumn, String nameColumn, String snapshotColumn) throws SQLException {
        UUID id = r.getObject(idColumn, UUID.class);
        return id == null ? null : new Person(id, r.getString(nameColumn), r.getString(snapshotColumn));
    }
}
