package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cns.spring_house.testsupport.LegacyVisits;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.vehiclemovement.dto.AssignMechanicRequestDTO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * V48: every new assignment event keeps the display names of that moment and
 * every real reassignment keeps its reason. Later renames never rewrite them.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleMechanicAssignmentSnapshotTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired VehicleWorkService work;
    @Autowired PlatformTransactionManager transactions;
    static final String URL = "/v1/vehicle-movements";
    UUID workshop, police, police2, admin, juan, maria, pedro;
    ExecutorService pool;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = TestDatabases.provision("vehicle_mechanic_snapshot_it");
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", TestDatabases::username);
        r.add("spring.datasource.password", TestDatabases::password);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach void setup() {
        pool = Executors.newCachedThreadPool();
        workshop = shop("QA A");
        police = user(workshop, "POLICE", "Policía", "Alfa"); police2 = user(workshop, "POLICE", "Policía", "Beta");
        admin = user(workshop, "ADMIN", "Ana", "Admin");
        juan = user(workshop, "OPERATOR", "Juan", "Pérez"); maria = user(workshop, "OPERATOR", "María", "López");
        pedro = user(workshop, "OPERATOR", "Pedro", "Gómez");
    }
    @AfterEach void stopPool() { pool.shutdownNow(); }

    // A ──────────────────────────────────────────────────────────────────
    @Test void initialAssignmentKeepsTheNamesOfThatMomentAndNoReason() throws Exception {
        // V49: the real path — admission without mechanic, then the start dialog assigns Juan.
        UUID id = admitted("P123ABC");
        assertThat(events(id)).isEmpty();
        start(id, juan, null, "un motivo que la primera asignación ignora").andExpect(status().isOk());
        var e = events(id);
        assertThat(e).hasSize(1);
        assertThat(e.get(0)).containsEntry("event_type", "INITIAL_ASSIGNMENT")
            .containsEntry("new_mechanic_user_id", juan).containsEntry("new_mechanic_name_at_event", "Juan Pérez")
            .containsEntry("changed_by_user_id", police).containsEntry("actor_name_at_event", "Policía Alfa");
        assertThat(e.get(0).get("previous_mechanic_name_at_event")).isNull();
        assertThat(e.get(0).get("reassignment_reason")).isNull();
        var event = history(id).get("events").get(0);
        assertThat(event.get("namesRecordedAtEvent").asBoolean()).isTrue();
        assertThat(event.get("reason").isNull()).isTrue();
        assertThat(event.get("newMechanic").get("nameAtEvent").asString()).isEqualTo("Juan Pérez");
        assertThat(event.get("changedBy").get("nameAtEvent").asString()).isEqualTo("Policía Alfa");
    }

    // B + C ──────────────────────────────────────────────────────────────
    @Test void reassignmentKeepsTrimmedReasonAndNamesThatSurviveRenames() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var visitBefore = row(id);
        assign(id, maria, juan, "   Juan no estaba disponible para ese turno  ", auth(police2, "POLICE"))
            .andExpect(status().isOk()).andExpect(jsonPath("id").value(id.toString()))
            .andExpect(jsonPath("mechanic.id").value(maria.toString()));
        assertThat(mechanicOf(id)).isEqualTo(maria);
        var changed = new TreeSet<String>();
        row(id).forEach((k, v) -> { if (!Objects.equals(v, visitBefore.get(k))) changed.add(k); });
        assertThat(changed).isSubsetOf("mechanic_user_id", "updated_at");
        var e = events(id);
        assertThat(e).hasSize(2);
        var reassignment = e.get(1);
        assertThat(reassignment).containsEntry("event_type", "REASSIGNMENT")
            .containsEntry("previous_mechanic_user_id", juan).containsEntry("previous_mechanic_name_at_event", "Juan Pérez")
            .containsEntry("new_mechanic_user_id", maria).containsEntry("new_mechanic_name_at_event", "María López")
            .containsEntry("changed_by_user_id", police2).containsEntry("actor_name_at_event", "Policía Beta")
            .containsEntry("reassignment_reason", "Juan no estaba disponible para ese turno");
        var before = events(id);

        rename(juan, "Juan Carlos", "Pérez Soto"); rename(maria, "María José", "López"); rename(police2, "Agente", "Beta Renombrado");
        assertThat(events(id)).isEqualTo(before);
        var latest = history(id).get("events").get(0);
        assertThat(latest.get("reason").asString()).isEqualTo("Juan no estaba disponible para ese turno");
        assertThat(latest.get("previousMechanic").get("nameAtEvent").asString()).isEqualTo("Juan Pérez");
        assertThat(latest.get("previousMechanic").get("name").asString()).isEqualTo("Juan Carlos Pérez Soto");
        assertThat(latest.get("newMechanic").get("nameAtEvent").asString()).isEqualTo("María López");
        assertThat(latest.get("newMechanic").get("name").asString()).isEqualTo("María José López");
        assertThat(latest.get("changedBy").get("nameAtEvent").asString()).isEqualTo("Policía Beta");
        assertThat(latest.get("changedBy").get("name").asString()).isEqualTo("Agente Beta Renombrado");
        // A new event after the rename records the NEW names, the old ones stay.
        assign(id, juan, maria, "Vuelve Juan", police()).andExpect(status().isOk());
        assertThat(events(id).get(2)).containsEntry("previous_mechanic_name_at_event", "María José López")
            .containsEntry("new_mechanic_name_at_event", "Juan Carlos Pérez Soto");
        assertThat(events(id).subList(0, 2)).isEqualTo(before);
    }

    // D ──────────────────────────────────────────────────────────────────
    @Test void missingBlankOrOversizedReasonIsRejectedWithoutAnyWrite() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var visit = row(id);
        var before = events(id);
        for (String reason : Arrays.asList(null, "", "   ", "\t\n ", "x".repeat(301), "😀".repeat(301)))
            assign(id, maria, juan, reason, police()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value(containsString("motivo")));
        assertThat(row(id)).isEqualTo(visit);
        assertThat(events(id)).isEqualTo(before);
        // Exactly the maximum (counted in characters, like the VARCHAR(300) column) is accepted.
        assign(id, maria, juan, "😀".repeat(300), police()).andExpect(status().isOk());
        assertThat(events(id).get(1).get("reassignment_reason")).isEqualTo("😀".repeat(300));
    }

    // E ──────────────────────────────────────────────────────────────────
    @Test void firstMechanicOfAnOpenLegacyVisitIsAnInitialAssignmentAtStartWithoutReason() throws Exception {
        UUID legacy = UUID.randomUUID();
        db.update("insert into vehicle_movement(id,workshop_id,plate,entry,created_at,updated_at) values(?,?,'P999ABC','2026-01-01 08:00:00',now(),now())",
            legacy, workshop);
        // V49: the correction path never gives a first mechanic, with or without reason.
        assign(legacy, juan, null, "  ", police()).andExpect(status().isBadRequest());
        assign(legacy, juan, null, "Visita sin mecánico desde antes", police()).andExpect(status().isBadRequest());
        assertThat(events(legacy)).isEmpty();
        assertThat(mechanicOf(legacy)).isNull();
        start(legacy, juan, null, null).andExpect(status().isOk());
        var e = events(legacy);
        assertThat(e).hasSize(1);
        assertThat(e.get(0)).containsEntry("event_type", "INITIAL_ASSIGNMENT").containsEntry("new_mechanic_name_at_event", "Juan Pérez")
            .containsEntry("actor_name_at_event", "Policía Alfa");
        assertThat(e.get(0).get("reassignment_reason")).isNull();
        assertThat(e.get(0).get("previous_mechanic_user_id")).isNull();
        // The event and the first start share the server instant; no earlier timestamp is fabricated.
        assertThat(e.get(0).get("changed_at")).isEqualTo(row(legacy).get("work_started_at"));
    }

    // F ──────────────────────────────────────────────────────────────────
    @Test void sameMechanicIsAnIdempotentNoOpThatStoresNoReason() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        assign(id, maria, juan, "Cambio real", police()).andExpect(status().isOk());
        var before = events(id);
        assign(id, maria, juan, "Reintento con otro texto", police()).andExpect(status().isOk());
        assign(id, maria, maria, "", police()).andExpect(status().isOk());
        assertThat(events(id)).isEqualTo(before);
        assertThat(count("select count(*) from vehicle_mechanic_assignment_event where reassignment_reason like 'Reintento%'")).isZero();
    }

    // G: real overlapping transactions ────────────────────────────────────
    @Test void twoOfficersOverlappingProduceOneEventWithTheWinnersNamesAndReason() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var held = holdTransaction(police, () -> work.assignMechanic(id,
            new AssignMechanicRequestDTO(maria, juan, "Gana Alfa", null)));
        Future<MvcResult> rival = pool.submit(() -> assign(id, pedro, juan, "Pierde Beta", auth(police2, "POLICE")).andReturn());
        awaitLockWait();
        assertThat(rival.isDone()).isFalse();
        held.release().countDown();
        held.result().get(20, TimeUnit.SECONDS);
        assertThat(rival.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        var e = events(id);
        assertThat(e).hasSize(2);
        assertThat(e.get(1)).containsEntry("new_mechanic_user_id", maria).containsEntry("actor_name_at_event", "Policía Alfa")
            .containsEntry("reassignment_reason", "Gana Alfa");
        assertThat(count("select count(*) from vehicle_mechanic_assignment_event where reassignment_reason='Pierde Beta'")).isZero();
    }

    // J + database guarantees ─────────────────────────────────────────────
    @Test void snapshotsAndReasonsCannotBeEditedOrDeletedAndTheDatabaseRequiresThemForNewRows() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        assign(id, maria, juan, "Motivo original", police()).andExpect(status().isOk());
        var before = events(id);
        assertThatThrownBy(() -> db.update("update vehicle_mechanic_assignment_event set reassignment_reason='editado' where vehicle_movement_id=?", id))
            .hasMessageContaining("solo insercion");
        assertThatThrownBy(() -> db.update("update vehicle_mechanic_assignment_event set actor_name_at_event='Otro' where vehicle_movement_id=?", id))
            .hasMessageContaining("solo insercion");
        assertThatThrownBy(() -> db.update("delete from vehicle_mechanic_assignment_event where vehicle_movement_id=?", id))
            .hasMessageContaining("solo insercion");
        assertThat(events(id)).isEqualTo(before);
        String insert = "insert into vehicle_mechanic_assignment_event(id,vehicle_movement_id,event_type,previous_mechanic_user_id,"
            + "new_mechanic_user_id,changed_by_user_id,changed_at,previous_mechanic_name_at_event,new_mechanic_name_at_event,"
            + "actor_name_at_event,reassignment_reason) values(gen_random_uuid(),?,?,?,?,?,now(),?,?,?,?)";
        // A recorded-names reassignment without reason, an initial assignment with reason, a blank reason, missing actor name.
        assertThatThrownBy(() -> db.update(insert, id, "REASSIGNMENT", maria, pedro, police, "María López", "Pedro Gómez", "Policía Alfa", null))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, id, "REASSIGNMENT", maria, pedro, police, "María López", "Pedro Gómez", "Policía Alfa", "  "))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, id, "REASSIGNMENT", maria, pedro, police, "María López", "Pedro Gómez", null, "x"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID other = preassigned("P456DEF", juan);
        assertThatThrownBy(() -> db.update(insert, other, "INITIAL_ASSIGNMENT", null, pedro, police, null, "Pedro Gómez", "Policía Alfa", "x"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(events(id)).isEqualTo(before);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    record Held<T>(Future<T> result, CountDownLatch release) {}

    <T> Held<T> holdTransaction(UUID actor, Supplier<T> work) throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var template = new TransactionTemplate(transactions);
        Future<T> result = pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(token(actor, "POLICE"));
            try {
                return template.execute(status -> {
                    T value = work.get();
                    acquired.countDown();
                    try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released"); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return value;
                });
            } finally { SecurityContextHolder.clearContext(); }
        });
        if (!acquired.await(20, TimeUnit.SECONDS)) result.get(1, TimeUnit.SECONDS);
        return new Held<>(result, release);
    }

    void awaitLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (count("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'") == 0) {
            if (System.nanoTime() > deadline) fail("The second transaction never waited for the first one's lock");
            Thread.sleep(20);
        }
    }

    JwtAuthenticationToken token(UUID id, String role) {
        Jwt jwt = Jwt.withTokenValue("qa").header("alg", "none").subject(id.toString())
            .claim("workshop", workshop.toString()).claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    UUID shop(String name) {
        UUID id = UUID.randomUUID();
        db.update("insert into workshop(id,name,address,is_active,created_at,updated_at) values(?,?,?,true,now(),now())", id, name, "QA");
        return id;
    }

    UUID user(UUID shop, String role, String firstName, String lastName) {
        UUID employee = UUID.randomUUID(), id = UUID.randomUUID();
        db.update("insert into employee(id,workshop_id,first_name,last_name,dpi,birth_date,created_at,updated_at) "
            + "values(?,?,?,?,?,'1990-01-01',now(),now())", employee, shop, firstName, lastName, employee.toString().substring(0, 20));
        db.update("insert into user_account(id,employee_id,role_id,username,password,is_active,created_at,updated_at) "
            + "select ?,?,id,?,'unused',true,now(),now() from role where name=?", id, employee, id.toString(), role);
        return id;
    }

    void rename(UUID account, String first, String last) {
        db.update("update employee set first_name=?, last_name=? where id=(select employee_id from user_account where id=?)", first, last, account);
    }

    RequestPostProcessor auth(UUID id, String role) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("workshop", workshop.toString()).claim("role", role))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
    RequestPostProcessor police() { return auth(police, "POLICE"); }

    /**
     * Visita con ASIGNACION PREVIA historica: el alta real (sin mecanico, V49) mas la asignacion
     * y el evento INITIAL_ASSIGNMENT que el alta escribia antes de V49 (ver {@link LegacyVisits}).
     */
    UUID admitted(String plate) throws Exception {
        String response = http.perform(multipart(URL).param("plate", plate).param("phone", "58452136").with(police()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }

    ResultActions start(UUID id, UUID mechanic, UUID expected, String reason) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("mechanicId", mechanic == null ? null : mechanic.toString());
        body.put("expectedMechanicId", expected == null ? null : expected.toString());
        if (reason != null) body.put("reason", reason);
        return http.perform(post(URL + "/" + id + "/start-work").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)).with(police()));
    }

    UUID preassigned(String plate, UUID mechanic) throws Exception {
        String response = http.perform(multipart(URL).param("plate", plate).param("phone", "58452136").with(police()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        LegacyVisits.preassign(db, id, mechanic, police);
        return id;
    }

    ResultActions assign(UUID id, UUID mechanic, UUID expected, String reason, RequestPostProcessor who) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("mechanicId", mechanic.toString());
        body.put("expectedMechanicId", expected == null ? null : expected.toString());
        if (reason != null) body.put("reason", reason);
        return http.perform(patch(URL + "/" + id + "/mechanic").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)).with(who));
    }

    JsonNode history(UUID id) throws Exception {
        return json.readTree(http.perform(get(URL + "/" + id + "/mechanic-history").with(auth(admin, "ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    List<Map<String, Object>> events(UUID visit) {
        return db.queryForList("select * from vehicle_mechanic_assignment_event where vehicle_movement_id=? order by changed_at, id", visit);
    }
    Map<String, Object> row(UUID id) { return db.queryForMap("select * from vehicle_movement where id=?", id); }
    UUID mechanicOf(UUID id) { return db.queryForObject("select mechanic_user_id from vehicle_movement where id=?", UUID.class, id); }
    long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
}
