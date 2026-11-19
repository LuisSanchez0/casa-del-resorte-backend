package com.cns.spring_house.vehiclemovement.service;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import com.cns.spring_house.testsupport.TestDatabases;
import java.sql.*;
import java.util.UUID;
class VehicleWorkMigrationTest {
 @Test void migrationPreservesExistingVisitWithoutInventedWork() throws Exception {
   String name="vehicle_work_migration_it";String url=TestDatabases.provision(name);
   try {
     Flyway.configure().dataSource(url,TestDatabases.username(),TestDatabases.password()).target("45").load().migrate();
     UUID shop=UUID.randomUUID(),visit=UUID.randomUUID();
     try(var c=DriverManager.getConnection(url,TestDatabases.username(),TestDatabases.password());var s=c.createStatement()) {
       s.execute("insert into workshop(id,name,address,is_active,created_at,updated_at) values('"+shop+"','QA','QA',true,now(),now())");
       s.execute("insert into vehicle_movement(id,workshop_id,plate,phone,notes,entry,exit,created_at,updated_at) values('"+visit+"','"+shop+"','P123ABC','58452136','historic','2026-01-01 08:00:00','2026-01-01 09:00:00',now(),now())");
     }
     Flyway.configure().dataSource(url,TestDatabases.username(),TestDatabases.password()).load().migrate();
     try(var c=DriverManager.getConnection(url,TestDatabases.username(),TestDatabases.password());var s=c.createStatement();var r=s.executeQuery("select * from vehicle_movement")) {
       assertThat(r.next()).isTrue();assertThat(r.getObject("id",UUID.class)).isEqualTo(visit);
       assertThat(r.getString("phone")).isEqualTo("58452136");assertThat(r.getString("notes")).isEqualTo("historic");
       assertThat(r.getTimestamp("entry").toLocalDateTime().toString()).isEqualTo("2026-01-01T08:00");
       assertThat(r.getObject("mechanic_user_id")).isNull();assertThat(r.getObject("work_started_at")).isNull();assertThat(r.getObject("work_finished_at")).isNull();
     }
   } finally {TestDatabases.drop(name);}
 }
}
