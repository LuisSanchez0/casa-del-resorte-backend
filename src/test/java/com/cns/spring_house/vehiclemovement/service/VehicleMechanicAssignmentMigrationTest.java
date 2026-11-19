package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import com.cns.spring_house.testsupport.TestDatabases;

/**
 * V47 on a database already at V46 with real-looking history: the existing
 * visits keep every column, no assignment event is invented for them, and the
 * checksums of V1-V46 still validate.
 */
class VehicleMechanicAssignmentMigrationTest {
    @Test void v47PreservesVisitsInventsNoHistoryAndValidatesEarlierChecksums() throws Exception {
        String name = "vehicle_mechanic_audit_migration_it";
        String url = TestDatabases.provision(name);
        try {
            Flyway.configure().dataSource(url, TestDatabases.username(), TestDatabases.password()).target("46").load().migrate();
            UUID shop = UUID.randomUUID(), employee = UUID.randomUUID(), mechanic = UUID.randomUUID();
            UUID finished = UUID.randomUUID(), open = UUID.randomUUID();
            List<Map<String, Object>> before;
            try (var c = DriverManager.getConnection(url, TestDatabases.username(), TestDatabases.password()); var s = c.createStatement()) {
                s.execute("insert into workshop(id,name,address,is_active,created_at,updated_at) values('" + shop + "','QA','QA',true,now(),now())");
                s.execute("insert into employee(id,workshop_id,first_name,last_name,dpi,birth_date,created_at,updated_at) values('"
                    + employee + "','" + shop + "','Juan','QA','" + employee.toString().substring(0, 20) + "','1990-01-01',now(),now())");
                s.execute("insert into user_account(id,employee_id,role_id,username,password,is_active,created_at,updated_at) select '"
                    + mechanic + "','" + employee + "',id,'qa_juan','unused',true,now(),now() from role where name='OPERATOR'");
                s.execute("insert into vehicle_movement(id,workshop_id,plate,phone,notes,entry,exit,mechanic_user_id,work_started_at,"
                    + "work_finished_at,started_by,finished_by,created_at,updated_at) values('" + finished + "','" + shop
                    + "','P123ABC','58452136','historic','2026-11-01 08:00:00','2026-11-01 12:00:00','" + mechanic
                    + "','2026-11-01T15:00:00Z','2026-11-01T17:00:00Z','" + mechanic + "','" + mechanic + "',now(),now())");
                s.execute("insert into vehicle_movement(id,workshop_id,plate,entry,mechanic_user_id,created_at,updated_at) values('"
                    + open + "','" + shop + "','P999ABC','2026-11-02 08:00:00','" + mechanic + "',now(),now())");
                before = rows(s);
            }
            // Pinned to V47: this test is about V47 over V46 data; V48 has its own migration test.
            var flyway = Flyway.configure().dataSource(url, TestDatabases.username(), TestDatabases.password()).target("47").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            try (var c = DriverManager.getConnection(url, TestDatabases.username(), TestDatabases.password()); var s = c.createStatement()) {
                assertThat(rows(s)).isEqualTo(before);
                try (var r = s.executeQuery("select count(*) from vehicle_mechanic_assignment_event")) {
                    r.next();
                    assertThat(r.getLong(1)).isZero();
                }
                try (var r = s.executeQuery("select version from flyway_schema_history where success order by installed_rank desc limit 1")) {
                    r.next();
                    assertThat(r.getString(1)).isEqualTo("47");
                }
            }
        } finally {
            TestDatabases.drop(name);
        }
    }

    private static List<Map<String, Object>> rows(java.sql.Statement s) throws Exception {
        var out = new ArrayList<Map<String, Object>>();
        try (var r = s.executeQuery("select * from vehicle_movement order by id")) {
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
