package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import com.cns.spring_house.testsupport.TestDatabases;

/**
 * V49 over data shaped exactly like V46-V48 left it: a visit finished before
 * pause tracking, one still in progress, one preassigned but not started (with
 * its V48 snapshot event), one open without mechanic and one closed historic
 * visit. Nothing is rewritten, no interval or pause is fabricated, and the V47
 * append-only guarantee survives.
 */
class VehicleWorkIntervalMigrationTest {
    @Test void v49AddsTheIntervalLedgerWithoutTouchingAnyExistingVisitOrEvent() throws Exception {
        String name = "vehicle_work_interval_migration_it";
        String url = TestDatabases.provision(name);
        try {
            Flyway.configure().dataSource(url, TestDatabases.username(), TestDatabases.password()).target("48").load().migrate();
            UUID shop = UUID.randomUUID(), police = UUID.randomUUID(), juan = UUID.randomUUID(), maria = UUID.randomUUID();
            UUID finished = UUID.randomUUID(), running = UUID.randomUUID(), preassigned = UUID.randomUUID(),
                unassigned = UUID.randomUUID(), closed = UUID.randomUUID();
            List<Map<String, Object>> visitsBefore, eventsBefore;
            try (var c = connect(url); var s = c.createStatement()) {
                s.execute("insert into workshop(id,name,address,is_active,created_at,updated_at) values('" + shop + "','QA','QA',true,now(),now())");
                account(s, shop, police, "POLICE", "Paula"); account(s, shop, juan, "OPERATOR", "Juan"); account(s, shop, maria, "OPERATOR", "María");
                visit(s, finished, shop, "P100AAA", maria, "'2026-11-17T14:00:00.123456Z'", "'2026-11-17T16:40:00.654321Z'", "'2026-11-17 11:00:00'", police);
                visit(s, running, shop, "P200BBB", juan, "'2026-11-18T15:00:00Z'", "null", "null", police);
                visit(s, preassigned, shop, "P300CCC", juan, "null", "null", "null", null);
                visit(s, unassigned, shop, "P400DDD", null, "null", "null", "null", null);
                visit(s, closed, shop, "P500EEE", null, "null", "null", "'2026-01-01 09:00:00'", null);
                // V47 event (no names) then a V48 reassignment with snapshots and reason on the finished visit.
                s.execute("insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
                    + "new_mechanic_user_id,changed_by_user_id,changed_at) values(gen_random_uuid(),'" + finished + "','INITIAL_ASSIGNMENT',null,'"
                    + juan + "','" + police + "','2026-11-17T13:00:00Z')");
                s.execute("insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
                    + "new_mechanic_user_id,changed_by_user_id,changed_at,previous_mechanic_name_at_event,new_mechanic_name_at_event,"
                    + "actor_name_at_event,reassignment_reason) values(gen_random_uuid(),'" + finished + "','REASSIGNMENT','" + juan + "','"
                    + maria + "','" + police + "','2026-11-17T13:30:00Z','Juan QA','María QA','Paula QA','Turno')");
                s.execute("insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
                    + "new_mechanic_user_id,changed_by_user_id,changed_at,new_mechanic_name_at_event,actor_name_at_event) values("
                    + "gen_random_uuid(),'" + preassigned + "','INITIAL_ASSIGNMENT',null,'" + juan + "','" + police
                    + "','2026-11-18T14:00:00Z','Juan QA','Paula QA')");
                visitsBefore = rows(s, "select * from vehicle_movement order by plate");
                eventsBefore = rows(s, "select * from vehicle_mechanic_assignment_event order by changed_at, id");
            }
            var flyway = Flyway.configure().dataSource(url, TestDatabases.username(), TestDatabases.password()).target("49").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            try (var c = connect(url); var s = c.createStatement()) {
                assertThat(rows(s, "select * from vehicle_movement order by plate")).isEqualTo(visitsBefore);
                assertThat(rows(s, "select * from vehicle_mechanic_assignment_event order by changed_at, id")).isEqualTo(eventsBefore);
                assertThat(rows(s, "select count(*) as n from vehicle_work_interval").get(0).get("n")).isEqualTo(0L);
                assertThat(rows(s, "select version from flyway_schema_history where success order by installed_rank desc limit 1")
                    .get(0).get("version")).isEqualTo("49");
                assertThatThrownBy(() -> s.executeUpdate("update vehicle_mechanic_assignment_event set reassignment_reason='x'"))
                    .hasMessageContaining("solo insercion");
                // The historic finish is final from now on; the first start of the running visit cannot be moved.
                assertThatThrownBy(() -> s.executeUpdate("update vehicle_movement set work_finished_at=work_finished_at + interval '1 minute' where id='" + finished + "'"))
                    .hasMessageContaining("definitivo");
                assertThatThrownBy(() -> s.executeUpdate("update vehicle_movement set work_started_at=now() where id='" + running + "'"))
                    .hasMessageContaining("primer inicio");
                // A preassigned visit can still change hands before its start (legacy correction path).
                assertThat(s.executeUpdate("update vehicle_movement set mechanic_user_id='" + maria + "' where id='" + preassigned + "'")).isEqualTo(1);
            }
        } finally {
            TestDatabases.drop(name);
        }
    }

    private static java.sql.Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, TestDatabases.username(), TestDatabases.password());
    }

    private static void visit(Statement s, UUID id, UUID shop, String plate, UUID mechanic, String started, String finished,
            String exit, UUID startedBy) throws Exception {
        s.execute("insert into vehicle_movement(id,workshop_id,plate,phone,notes,entry,exit,mechanic_user_id,work_started_at,"
            + "work_finished_at,started_by,finished_by,created_at,updated_at) values('" + id + "','" + shop + "','" + plate
            + "','58452136','histórico','2026-11-17 07:00:00'," + exit + "," + (mechanic == null ? "null" : "'" + mechanic + "'")
            + "," + started + "," + finished + "," + (startedBy == null ? "null" : "'" + startedBy + "'") + ","
            + ("null".equals(finished) ? "null" : "'" + startedBy + "'") + ",'2026-11-17 07:00:00','2026-11-17 07:00:00')");
    }

    private static void account(Statement s, UUID shop, UUID id, String role, String firstName) throws Exception {
        UUID employee = UUID.randomUUID();
        s.execute("insert into employee(id,workshop_id,first_name,last_name,dpi,birth_date,created_at,updated_at) values('"
            + employee + "','" + shop + "','" + firstName + "','QA','" + employee.toString().substring(0, 20) + "','1990-01-01',now(),now())");
        s.execute("insert into user_account(id,employee_id,role_id,username,password,is_active,created_at,updated_at) select '"
            + id + "','" + employee + "',id,'" + id + "','unused',true,now(),now() from role where name='" + role + "'");
    }

    private static List<Map<String, Object>> rows(Statement s, String sql) throws Exception {
        var out = new ArrayList<Map<String, Object>>();
        try (var r = s.executeQuery(sql)) {
            var meta = r.getMetaData();
            while (r.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnName(i), r.getObject(i));
                out.add(row);
            }
        }
        return out;
    }
}
