package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import com.cns.spring_house.testsupport.TestDatabases;

/**
 * V48 over a database that already holds V47 events: every existing event keeps
 * all its V47 values, the new columns stay NULL (no names or reasons are
 * invented), the append-only trigger still rejects UPDATE and DELETE, and the
 * checksums of V1-V47 still validate.
 */
class VehicleMechanicSnapshotMigrationTest {
    @Test void v48KeepsV47EventsUntouchedInventsNothingAndKeepsTheTrigger() throws Exception {
        String name = "vehicle_mechanic_snapshot_migration_it";
        String url = TestDatabases.provision(name);
        try {
            Flyway.configure().dataSource(url, TestDatabases.username(), TestDatabases.password()).target("47").load().migrate();
            UUID shop = UUID.randomUUID(), visit = UUID.randomUUID(), legacyOpen = UUID.randomUUID();
            UUID juan = UUID.randomUUID(), maria = UUID.randomUUID(), police = UUID.randomUUID();
            List<Map<String, Object>> before;
            try (var c = DriverManager.getConnection(url, TestDatabases.username(), TestDatabases.password()); var s = c.createStatement()) {
                s.execute("insert into workshop(id,name,address,is_active,created_at,updated_at) values('" + shop + "','QA','QA',true,now(),now())");
                account(s, shop, juan, "OPERATOR", "Juan");
                account(s, shop, maria, "OPERATOR", "María");
                account(s, shop, police, "POLICE", "Policía");
                s.execute("insert into vehicle_movement(id,workshop_id,plate,entry,mechanic_user_id,created_at,updated_at) values('"
                    + visit + "','" + shop + "','P123ABC','2026-11-17 08:00:00','" + maria + "',now(),now())");
                s.execute("insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
                    + "new_mechanic_user_id,changed_by_user_id,changed_at) values(gen_random_uuid(),'" + visit + "','INITIAL_ASSIGNMENT',null,'"
                    + juan + "','" + police + "','2026-11-17T14:00:00.123456Z')");
                s.execute("insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
                    + "new_mechanic_user_id,changed_by_user_id,changed_at) values(gen_random_uuid(),'" + visit + "','REASSIGNMENT','"
                    + juan + "','" + maria + "','" + police + "','2026-11-17T14:30:00.654321Z')");
                // V47 also allowed a REASSIGNMENT without previous mechanic: the first assignment of an old open visit.
                s.execute("insert into vehicle_movement(id,workshop_id,plate,entry,mechanic_user_id,created_at,updated_at) values('"
                    + legacyOpen + "','" + shop + "','P900LEG','2026-11-10 08:00:00','" + juan + "',now(),now())");
                s.execute("insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
                    + "new_mechanic_user_id,changed_by_user_id,changed_at) values(gen_random_uuid(),'" + legacyOpen + "','REASSIGNMENT',null,'"
                    + juan + "','" + police + "','2026-11-17T15:00:00.000001Z')");
                before = rows(s, "select * from vehicle_mechanic_assignment_event order by changed_at");
            }
            // Pinned to V48: later migrations (V49 work intervals) have their own migration test.
            var flyway = Flyway.configure().dataSource(url, TestDatabases.username(), TestDatabases.password()).target("48").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            try (var c = DriverManager.getConnection(url, TestDatabases.username(), TestDatabases.password()); var s = c.createStatement()) {
                var after = rows(s, "select * from vehicle_mechanic_assignment_event order by changed_at");
                assertThat(after).hasSize(3);
                for (int i = 0; i < after.size(); i++) {
                    var row = after.get(i);
                    for (var column : before.get(i).keySet()) assertThat(row.get(column)).as(column).isEqualTo(before.get(i).get(column));
                    for (var column : List.of("previous_mechanic_name_at_event", "new_mechanic_name_at_event", "actor_name_at_event",
                            "reassignment_reason"))
                        assertThat(row.get(column)).as(column).isNull();
                }
                assertThatThrownBy(() -> s.executeUpdate("update vehicle_mechanic_assignment_event set reassignment_reason='inventado'"))
                    .hasMessageContaining("solo insercion");
                assertThatThrownBy(() -> s.executeUpdate("update vehicle_mechanic_assignment_event set new_mechanic_name_at_event='Juan'"))
                    .hasMessageContaining("solo insercion");
                assertThatThrownBy(() -> s.executeUpdate("delete from vehicle_mechanic_assignment_event")).hasMessageContaining("solo insercion");
                assertThat(rows(s, "select version from flyway_schema_history where success order by installed_rank desc limit 1")
                    .get(0).get("version")).isEqualTo("48");
            }
        } finally {
            TestDatabases.drop(name);
        }
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
