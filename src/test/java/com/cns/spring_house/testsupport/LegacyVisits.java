package com.cns.spring_house.testsupport;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Datos con la forma que dejaba el alta ANTES de V49: una visita abierta, sin
 * iniciar, con mecanico asignado desde el ingreso y su evento
 * INITIAL_ASSIGNMENT (V47 + nombres de V48, actor = quien registro el ingreso).
 * <p>
 * Desde V49 el alta ya no asigna mecanico, asi que este estado solo existe en
 * filas historicas; las pruebas de compatibilidad lo siembran por SQL, igual
 * que lo habria escrito el codigo anterior, sin pasar por ninguna ruta nueva.
 */
public final class LegacyVisits {

    private LegacyVisits() {
    }

    public static void preassign(JdbcTemplate db, UUID visit, UUID mechanic, UUID actor) {
        db.update("update vehicle_movement set mechanic_user_id=? where id=? and work_started_at is null", mechanic, visit);
        db.update("""
            insert into vehicle_mechanic_assignment_event(id, vehicle_movement_id, event_type, previous_mechanic_user_id,
                new_mechanic_user_id, changed_by_user_id, changed_at, new_mechanic_name_at_event, actor_name_at_event)
            select gen_random_uuid(), ?, 'INITIAL_ASSIGNMENT', null, m.id, a.id, now(),
                   btrim(me.first_name || ' ' || me.last_name), btrim(ae.first_name || ' ' || ae.last_name)
              from user_account m join employee me on me.id = m.employee_id,
                   user_account a join employee ae on ae.id = a.employee_id
             where m.id = ? and a.id = ?
            """, visit, mechanic, actor);
    }

    /** Mecanico vigente de la visita (null si no tiene). */
    public static UUID mechanicOf(JdbcTemplate db, UUID visit) {
        return db.queryForObject("select mechanic_user_id from vehicle_movement where id=?", UUID.class, visit);
    }

    /** Cuerpo de start-work que conserva al mecanico vigente (o ninguno): el inicio "tal cual" de una visita. */
    public static String keepCurrentMechanic(JdbcTemplate db, UUID visit) {
        UUID current = mechanicOf(db, visit);
        String id = current == null ? "null" : "\"" + current + "\"";
        return "{\"mechanicId\":" + id + ",\"expectedMechanicId\":" + id + "}";
    }
}
