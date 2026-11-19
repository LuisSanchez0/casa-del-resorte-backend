package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
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
import com.cns.spring_house.vehiclemovement.dto.StartWorkRequestDTO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V47: every real mechanic assignment of a visit appends exactly one truthful,
 * immutable audit event in the same transaction; no-ops and rejections append
 * nothing. Runs through the real HTTP security chain and PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleMechanicAssignmentAuditTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired VehicleWorkService work;
    @Autowired PlatformTransactionManager transactions;
    static final String URL = "/v1/vehicle-movements", REPORT = "/v1/vehicle-work-report";
    /** Since V48 every real change must carry a reason. */
    static final String REASON = "QA: cambio de turno";
    UUID workshop, foreign, police, police2, admin, juan, maria, pedro, inactive, foreignOperator;
    ExecutorService pool;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = TestDatabases.provision("vehicle_mechanic_audit_it");
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", TestDatabases::username);
        r.add("spring.datasource.password", TestDatabases::password);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach void setup() {
        pool = Executors.newCachedThreadPool();
        workshop = shop("QA A"); foreign = shop("QA B");
        police = user(workshop, "POLICE", true, "Agente A"); police2 = user(workshop, "POLICE", true, "Agente B");
        admin = user(workshop, "ADMIN", true, "Ana");
        juan = user(workshop, "OPERATOR", true, "Juan"); maria = user(workshop, "OPERATOR", true, "María");
        pedro = user(workshop, "OPERATOR", true, "Pedro"); inactive = user(workshop, "OPERATOR", false, "Inés");
        foreignOperator = user(foreign, "OPERATOR", true, "Otto");
    }
    @AfterEach void stopPool() { pool.shutdownNow(); }

    // 1, 2, 4, 5 ──────────────────────────────────────────────────────────

    @Test void legacyPreassignedVisitLogsEachRealCorrectionOnce() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        UUID id = preassigned("P123ABC", juan);
        var events = events(id);
        assertThat(events).hasSize(1);
        assertEvent(events.get(0), id, "INITIAL_ASSIGNMENT", null, juan, police);
        assertThat(((java.sql.Timestamp) events.get(0).get("changed_at")).toInstant()).isAfter(before);

        assign(id, body(maria, juan), auth(police2, workshop, "POLICE")).andExpect(status().isOk());
        events = events(id);
        assertThat(events).hasSize(2);
        assertEvent(events.get(1), id, "REASSIGNMENT", juan, maria, police2);
        assertThat(mechanicOf(id)).isEqualTo(maria);

        // Double click / retried identical request after commit: no new event.
        assign(id, body(maria, juan), police()).andExpect(status().isOk());
        assign(id, body(maria, maria), police()).andExpect(status().isOk());
        assertThat(events(id)).hasSize(2);

        // Juan -> María -> Juan is a legitimate cycle, not a duplicate.
        assign(id, body(juan, maria), police()).andExpect(status().isOk());
        events = events(id);
        assertThat(events).hasSize(3);
        assertEvent(events.get(2), id, "REASSIGNMENT", maria, juan, police);
        var history = history(id, police());
        assertThat(history.get("total").asLong()).isEqualTo(3);
        assertThat(types(history)).containsExactly("REASSIGNMENT", "REASSIGNMENT", "INITIAL_ASSIGNMENT");
        var newest = history.get("events").get(0);
        assertThat(newest.get("previousMechanic").get("name").asString()).isEqualTo("María QA");
        assertThat(newest.get("newMechanic").get("name").asString()).isEqualTo("Juan QA");
        assertThat(newest.get("changedBy").get("name").asString()).isEqualTo("Agente A QA");
        assertThat(history.get("events").get(2).get("previousMechanic").isNull()).isTrue();
        assertThat(mechanicOf(id)).isEqualTo(juan);
    }

    // 3, 11 ───────────────────────────────────────────────────────────────

    @Test void legacyVisitsGetNoFabricatedHistoryAndAnOpenOneLogsItsFirstAssignment() throws Exception {
        UUID open = legacy("P999ABC", null), closed = legacy("P888ABC", "2026-01-01 09:00:00");
        for (UUID id : List.of(open, closed)) {
            var history = history(id, police());
            assertThat(history.get("total").asLong()).isZero();
            assertThat(history.get("events").size()).isZero();
            assertThat(history(id, admin()).get("total").asLong()).isZero();
        }
        assign(closed, body(juan, null), police()).andExpect(status().isBadRequest());
        assertThat(events(closed)).isEmpty();
        // V49: an open visit without a mechanic gets it when work STARTS, never through the correction path.
        assign(open, body(juan, null), police()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("detail").value(org.hamcrest.Matchers.containsString("Iniciar trabajo")));
        assertThat(events(open)).isEmpty();
        http.perform(post(URL + "/" + open + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content("{\"mechanicId\":\"" + juan + "\",\"expectedMechanicId\":null}").with(police()))
            .andExpect(status().isOk()).andExpect(jsonPath("mechanic.id").value(juan.toString()));
        var events = events(open);
        assertThat(events).hasSize(1);
        assertEvent(events.get(0), open, "INITIAL_ASSIGNMENT", null, juan, police);
        assertThat(row(open).get("entry").toString()).startsWith("2026-01-01 08:00");
    }

    // 6, 8 ────────────────────────────────────────────────────────────────

    @Test void startedFinishedExitedAndStaleRequestsAppendNothing() throws Exception {
        UUID started = preassigned("P100AAA", juan); action(started, "start-work", 200);
        UUID finished = preassigned("P200BBB", juan); action(finished, "start-work", 200); action(finished, "finish-work", 200);
        UUID exited = preassigned("P300CCC", juan); action(exited, "register-exit", 200);
        for (UUID id : List.of(started, finished, exited)) {
            var before = row(id);
            assign(id, body(maria, juan), police()).andExpect(status().isBadRequest());
            assertThat(events(id)).hasSize(1);
            assertThat(row(id)).isEqualTo(before);
        }
        UUID stale = preassigned("P400DDD", juan);
        assign(stale, body(pedro, juan), police()).andExpect(status().isOk());
        var before = row(stale);
        assign(stale, body(maria, juan), police()).andExpect(status().isConflict());
        assign(stale, "{\"mechanicId\":\"" + maria + "\"}", police()).andExpect(status().isConflict());
        assertThat(events(stale)).hasSize(2);
        assertThat(row(stale)).isEqualTo(before);
    }

    // 7 ───────────────────────────────────────────────────────────────────

    @Test void invalidReplacementsWrongRolesAndForeignWorkshopsChangeNothing() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var before = row(id);
        for (UUID invalid : List.of(foreignOperator, inactive, police, admin, UUID.randomUUID()))
            assign(id, body(invalid, juan), police()).andExpect(status().isBadRequest());
        assign(id, "{\"mechanicId\":\"" + maria + "\",\"expectedMechanicId\":\"" + juan
            + "\",\"changedBy\":\"" + police2 + "\"}", police()).andExpect(status().isBadRequest());
        for (String role : List.of("ADMIN", "OPERATOR", "SELLER", "CONTROL", "SECRETARY"))
            assign(id, body(maria, juan), auth(admin, workshop, role)).andExpect(status().isForbidden());
        UUID outsider = user(foreign, "POLICE", true, "Fausto");
        assign(id, body(maria, juan), auth(outsider, foreign, "POLICE")).andExpect(status().isNotFound());
        http.perform(patch(URL + "/" + id + "/mechanic").contentType(MediaType.APPLICATION_JSON).content(body(maria, juan)))
            .andExpect(status().isUnauthorized());
        assertThat(row(id)).isEqualTo(before);
        assertThat(events(id)).hasSize(1);
        // V49: admission never takes a mechanic; any mechanicId is refused with no visit and no event.
        long all = count("select count(*) from vehicle_mechanic_assignment_event");
        http.perform(multipart(URL).param("plate", "P555EEE").param("phone", "58452136")
            .param("mechanicId", foreignOperator.toString()).with(police())).andExpect(status().isBadRequest());
        assertThat(count("select count(*) from vehicle_movement where plate='P555EEE'")).isZero();
        assertThat(count("select count(*) from vehicle_mechanic_assignment_event")).isEqualTo(all);
    }

    // C. history authorization, scope and bounds ────────────────────────────

    @Test void historyIsReadOnlyScopedBoundedAndDeterministic() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        assign(id, body(maria, juan), police()).andExpect(status().isOk());
        assign(id, body(pedro, maria), police()).andExpect(status().isOk());
        assign(id, body(juan, pedro), police()).andExpect(status().isOk());
        var all = history(id, police());
        assertThat(all.get("total").asLong()).isEqualTo(4);
        assertThat(all.get("visitId").asString()).isEqualTo(id.toString());
        var seen = new ArrayList<String>();
        for (int page = 0; page < 4; page++) {
            var slice = json(http.perform(get(URL + "/" + id + "/mechanic-history").param("page", "" + page)
                .param("size", "1").with(admin())).andExpect(status().isOk()));
            assertThat(slice.get("total").asLong()).isEqualTo(4);
            seen.add(slice.get("events").get(0).get("id").asString());
        }
        assertThat(seen).containsExactlyElementsOf(values(all.get("events"), "id"));
        for (String role : List.of("OPERATOR", "SELLER", "CONTROL", "SECRETARY"))
            http.perform(get(URL + "/" + id + "/mechanic-history").with(auth(admin, workshop, role))).andExpect(status().isForbidden());
        UUID foreignPolice = user(foreign, "POLICE", true, "Fausto"), foreignAdmin = user(foreign, "ADMIN", true, "Fabiola");
        http.perform(get(URL + "/" + id + "/mechanic-history").with(auth(foreignPolice, foreign, "POLICE"))).andExpect(status().isNotFound());
        http.perform(get(URL + "/" + id + "/mechanic-history").with(auth(foreignAdmin, foreign, "ADMIN"))).andExpect(status().isNotFound());
        http.perform(get(URL + "/" + UUID.randomUUID() + "/mechanic-history").with(police())).andExpect(status().isNotFound());
        http.perform(get(URL + "/" + id + "/mechanic-history")).andExpect(status().isUnauthorized());
        for (String size : List.of("0", "51"))
            http.perform(get(URL + "/" + id + "/mechanic-history").param("size", size).with(police())).andExpect(status().isBadRequest());
        http.perform(get(URL + "/" + id + "/mechanic-history").param("page", "-1").with(police())).andExpect(status().isBadRequest());
        for (var write : List.of(post(URL + "/" + id + "/mechanic-history"), put(URL + "/" + id + "/mechanic-history"),
                delete(URL + "/" + id + "/mechanic-history")))
            assertThat(http.perform(write.with(police())).andReturn().getResponse().getStatus()).isIn(403, 405);
        assertThat(events(id)).hasSize(4);
    }

    // 16 (DB side): append-only and history survives users being changed ───

    @Test void eventsAreImmutableAndOutliveDeactivationTransferAndRenames() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        assign(id, body(maria, juan), police()).andExpect(status().isOk());
        var before = events(id);
        assertThatThrownBy(() -> db.update("update vehicle_mechanic_assignment_event set new_mechanic_user_id=? "
            + "where vehicle_movement_id=?", pedro, id)).hasMessageContaining("solo insercion");
        assertThatThrownBy(() -> db.update("delete from vehicle_mechanic_assignment_event where vehicle_movement_id=?", id))
            .hasMessageContaining("solo insercion");
        assertThatThrownBy(() -> db.update("delete from user_account where id=?", juan))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        db.update("update user_account set is_active=false where id=?", juan);
        db.update("update employee set workshop_id=? where id=(select employee_id from user_account where id=?)", foreign, maria);
        db.update("update employee set first_name='María José' where id=(select employee_id from user_account where id=?)", maria);
        assertThat(events(id)).isEqualTo(before);
        var history = history(id, admin());
        assertThat(history.get("total").asLong()).isEqualTo(2);
        var change = history.get("events").get(0);
        assertThat(change.get("previousMechanic").get("id").asString()).isEqualTo(juan.toString());
        assertThat(change.get("newMechanic").get("id").asString()).isEqualTo(maria.toString());
        // Names are the CURRENT ones (documented); the UUID is the canonical identity.
        assertThat(change.get("newMechanic").get("name").asString()).isEqualTo("María José QA");
    }

    // 12, 14 ──────────────────────────────────────────────────────────────

    @Test void samePlateVisitsHaveSeparateHistoriesAndReportCreditsOnlyTheFinalMechanic() throws Exception {
        UUID first = preassigned("P123ABC", juan);
        assign(first, body(maria, juan), police()).andExpect(status().isOk());
        action(first, "start-work", 200);
        action(first, "finish-work", 200);
        action(first, "register-exit", 200);
        UUID second = preassigned("P123ABC", pedro);
        assertThat(second).isNotEqualTo(first);
        assertThat(types(history(first, police()))).containsExactly("REASSIGNMENT", "INITIAL_ASSIGNMENT");
        var secondHistory = history(second, police());
        assertThat(types(secondHistory)).containsExactly("INITIAL_ASSIGNMENT");
        assertThat(secondHistory.get("events").get(0).get("newMechanic").get("id").asString()).isEqualTo(pedro.toString());

        var report = json(http.perform(get(REPORT).with(admin())).andExpect(status().isOk()));
        assertThat(report.get("total").asLong()).isEqualTo(2);
        assertThat(report.get("summary").get("completed").asLong()).isEqualTo(1);
        assertThat(values(report.get("mechanics"), "mechanicId")).containsExactlyInAnyOrder(maria.toString(), pedro.toString());
        for (int i = 0; i < report.get("mechanics").size(); i++) {
            var group = report.get("mechanics").get(i);
            if (group.get("mechanicId").asString().equals(maria.toString())) assertThat(group.get("completed").asLong()).isEqualTo(1);
            else assertThat(group.get("completed").asLong()).isZero();
        }
        var byFinish = json(http.perform(get(REPORT).param("dateBasis", "FINISHED").with(admin())).andExpect(status().isOk()));
        assertThat(values(byFinish.get("mechanics"), "mechanicId")).containsExactly(maria.toString());
        assertThat(row(first).get("work_started_at")).isNotNull();
        assertThat(mechanicOf(first)).isEqualTo(maria);
    }

    // 10: forced failures roll back both sides ──────────────────────────────

    @Test void aFailingEventInsertRollsBackTheVisitAndAFailingVisitUpdateLeavesNoEvent() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        db.execute("CREATE OR REPLACE FUNCTION qa_forced_failure() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'qa forced failure'; END $$");
        try {
            db.execute("CREATE TRIGGER qa_fail_event BEFORE INSERT ON vehicle_mechanic_assignment_event FOR EACH ROW "
                + "WHEN (NEW.new_mechanic_user_id = '" + pedro + "') EXECUTE FUNCTION qa_forced_failure()");
            db.execute("CREATE TRIGGER qa_fail_visit BEFORE UPDATE ON vehicle_movement FOR EACH ROW "
                + "WHEN (NEW.mechanic_user_id = '" + maria + "') EXECUTE FUNCTION qa_forced_failure()");
            var before = row(id);
            // Visit updated to Pedro, then the event insert fails: the update must roll back.
            assign(id, body(pedro, juan), police()).andExpect(status().is5xxServerError());
            assertThat(row(id)).isEqualTo(before);
            assertThat(events(id)).hasSize(1);
            // The visit update itself fails: no event may exist without its assignment.
            assign(id, body(maria, juan), police()).andExpect(status().is5xxServerError());
            assertThat(row(id)).isEqualTo(before);
            assertThat(events(id)).hasSize(1);
            // V49: a start whose INITIAL_ASSIGNMENT insert fails leaves no mechanic, no start and no interval.
            String created = http.perform(multipart(URL).param("plate", "P777GGG").param("phone", "58452136")
                .with(police())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            UUID fresh = UUID.fromString(json.readTree(created).get("id").asString());
            var freshBefore = row(fresh);
            http.perform(post(URL + "/" + fresh + "/start-work").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mechanicId\":\"" + pedro + "\",\"expectedMechanicId\":null}").with(police()))
                .andExpect(status().is5xxServerError());
            assertThat(row(fresh)).isEqualTo(freshBefore);
            assertThat(events(fresh)).isEmpty();
            assertThat(count("select count(*) from vehicle_work_interval where vehicle_movement_id=?", fresh)).isZero();
        } finally {
            db.execute("DROP TRIGGER IF EXISTS qa_fail_event ON vehicle_mechanic_assignment_event");
            db.execute("DROP TRIGGER IF EXISTS qa_fail_visit ON vehicle_movement");
            db.execute("DROP FUNCTION IF EXISTS qa_forced_failure()");
        }
        assign(id, body(pedro, juan), police()).andExpect(status().isOk());
        assertThat(events(id)).hasSize(2);
    }

    // 9: real overlapping transactions ──────────────────────────────────────

    @Test void twoDistinctConcurrentReassignmentsYieldOneChangeAndOneEvent() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var held = holdTransaction(police, () -> work.assignMechanic(id, new AssignMechanicRequestDTO(maria, juan, REASON, null)));
        Future<MvcResult> rival = pool.submit(() -> assign(id, body(pedro, juan), auth(police2, workshop, "POLICE")).andReturn());
        awaitLockWait();
        assertThat(rival.isDone()).isFalse();
        held.release().countDown();
        held.result().get(20, TimeUnit.SECONDS);
        assertThat(rival.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(mechanicOf(id)).isEqualTo(maria);
        var events = events(id);
        assertThat(events).hasSize(2);
        assertEvent(events.get(1), id, "REASSIGNMENT", juan, maria, police);
    }

    @Test void concurrentDuplicateOfTheSameChangeAppendsOnlyOnce() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var held = holdTransaction(police, () -> work.assignMechanic(id, new AssignMechanicRequestDTO(maria, juan, REASON, null)));
        Future<MvcResult> duplicate = pool.submit(() -> assign(id, body(maria, juan), police()).andReturn());
        awaitLockWait();
        held.release().countDown();
        held.result().get(20, TimeUnit.SECONDS);
        assertThat(duplicate.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(events(id)).hasSize(2);
    }

    @Test void reassignmentVersusStartInBothOrdersKeepsAssignmentAndHistoryInAgreement() throws Exception {
        UUID first = preassigned("P100AAA", juan);
        var reassign = holdTransaction(police, () -> work.assignMechanic(first, new AssignMechanicRequestDTO(maria, juan, REASON, null)));
        // The start dialog still shows Juan: once the correction commits, its expectation is stale (409, no write).
        Future<MvcResult> start = pool.submit(() -> http.perform(post(URL + "/" + first + "/start-work")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mechanicId\":\"" + juan + "\",\"expectedMechanicId\":\"" + juan + "\"}").with(police())).andReturn());
        awaitLockWait();
        reassign.release().countDown();
        reassign.result().get(20, TimeUnit.SECONDS);
        assertThat(start.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(mechanicOf(first)).isEqualTo(maria);
        assertThat(row(first).get("work_started_at")).isNull();
        assertThat(events(first)).hasSize(2);
        assertThat(events(first).get(1).get("new_mechanic_user_id")).isEqualTo(maria);
        assertThat(count("select count(*) from vehicle_work_interval where vehicle_movement_id=?", first)).isZero();

        UUID second = preassigned("P200BBB", juan);
        var started = holdTransaction(police, () -> work.start(second, new StartWorkRequestDTO(juan, juan, null, null)));
        Future<MvcResult> late = pool.submit(() -> assign(second, body(maria, juan), police()).andReturn());
        awaitLockWait();
        started.release().countDown();
        var startedAt = started.result().get(20, TimeUnit.SECONDS).workStartedAt();
        assertThat(late.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        assertThat(mechanicOf(second)).isEqualTo(juan);
        assertThat(events(second)).hasSize(1);
        assertThat(((java.sql.Timestamp) row(second).get("work_started_at")).toInstant()).isEqualTo(startedAt);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    record Held<T>(Future<T> result, CountDownLatch release) {}

    /** Runs {@code work} as {@code actor} in a transaction that stays open, holding its locks, until released. */
    <T> Held<T> holdTransaction(UUID actor, Supplier<T> work) throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var template = new TransactionTemplate(transactions);
        Future<T> result = pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(token(actor, workshop, "POLICE"));
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

    /** Proves the second transaction really overlaps: PostgreSQL shows it waiting on the first one's lock. */
    void awaitLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (count("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'") == 0) {
            if (System.nanoTime() > deadline) fail("The second transaction never waited for the first one's lock");
            Thread.sleep(20);
        }
    }

    JwtAuthenticationToken token(UUID id, UUID shop, String role) {
        Jwt jwt = Jwt.withTokenValue("qa").header("alg", "none").subject(id.toString())
            .claim("workshop", shop.toString()).claim("role", role).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    UUID shop(String name) {
        UUID id = UUID.randomUUID();
        db.update("insert into workshop(id,name,address,is_active,created_at,updated_at) values(?,?,?,true,now(),now())", id, name, "QA");
        return id;
    }

    UUID user(UUID shop, String role, boolean active, String firstName) {
        UUID employee = UUID.randomUUID(), id = UUID.randomUUID();
        db.update("insert into employee(id,workshop_id,first_name,last_name,dpi,birth_date,created_at,updated_at) "
            + "values(?,?,?,?,?,'1990-01-01',now(),now())", employee, shop, firstName, "QA", employee.toString().substring(0, 20));
        db.update("insert into user_account(id,employee_id,role_id,username,password,is_active,created_at,updated_at) "
            + "select ?,?,id,?,'unused',?,now(),now() from role where name=?", id, employee, id.toString(), active, role);
        return id;
    }

    RequestPostProcessor auth(UUID id, UUID shop, String role) {
        return jwt().jwt(j -> j.subject(id.toString()).claim("workshop", shop.toString()).claim("role", role))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
    RequestPostProcessor police() { return auth(police, workshop, "POLICE"); }
    RequestPostProcessor admin() { return auth(admin, workshop, "ADMIN"); }

    /**
     * Visita con ASIGNACION PREVIA historica: el alta real (sin mecanico, V49) mas la asignacion
     * y el evento INITIAL_ASSIGNMENT que el alta escribia antes de V49 (ver {@link LegacyVisits}).
     */
    UUID preassigned(String plate, UUID mechanic) throws Exception {
        String response = http.perform(multipart(URL).param("plate", plate).param("phone", "58452136").with(police()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());
        LegacyVisits.preassign(db, id, mechanic, police);
        return id;
    }

    UUID legacy(String plate, String exit) {
        UUID id = UUID.randomUUID();
        db.update("insert into vehicle_movement(id,workshop_id,plate,entry,exit,created_at,updated_at) "
            + "values(?,?,?,'2026-01-01 08:00:00',?::timestamp,now(),now())", id, workshop, plate, exit);
        return id;
    }

    /** start-work conserva al mecanico vigente (asignacion previa historica); el resto va sin cuerpo. */
    void action(UUID id, String action, int status) throws Exception {
        var request = post(URL + "/" + id + "/" + action).with(police());
        if (action.equals("start-work"))
            request.contentType(MediaType.APPLICATION_JSON).content(LegacyVisits.keepCurrentMechanic(db, id));
        http.perform(request).andExpect(status().is(status));
    }

    ResultActions assign(UUID id, String body, RequestPostProcessor who) throws Exception {
        return http.perform(patch(URL + "/" + id + "/mechanic").contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    String body(UUID mechanic, UUID expected) {
        return "{\"mechanicId\":\"" + mechanic + "\",\"expectedMechanicId\":" + (expected == null ? "null" : "\"" + expected + "\"")
            + ",\"reason\":\"" + REASON + "\"}";
    }

    JsonNode history(UUID id, RequestPostProcessor who) throws Exception {
        return json(http.perform(get(URL + "/" + id + "/mechanic-history").with(who)).andExpect(status().isOk()));
    }

    JsonNode json(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    List<String> values(JsonNode array, String field) {
        var out = new ArrayList<String>();
        for (int i = 0; i < array.size(); i++) out.add(array.get(i).get(field).asString());
        return out;
    }
    List<String> types(JsonNode history) { return values(history.get("events"), "eventType"); }

    /** Events of one visit in insertion (chronological) order. */
    List<Map<String, Object>> events(UUID visit) {
        return db.queryForList("select * from vehicle_mechanic_assignment_event where vehicle_movement_id=? "
            + "order by changed_at, id", visit);
    }

    void assertEvent(Map<String, Object> event, UUID visit, String type, UUID previous, UUID next, UUID actor) {
        assertThat(event.get("vehicle_movement_id")).isEqualTo(visit);
        assertThat(event.get("event_type")).isEqualTo(type);
        assertThat(event.get("previous_mechanic_user_id")).isEqualTo(previous);
        assertThat(event.get("new_mechanic_user_id")).isEqualTo(next);
        assertThat(event.get("changed_by_user_id")).isEqualTo(actor);
        assertThat(event.get("changed_at")).isNotNull();
    }

    Map<String, Object> row(UUID id) { return db.queryForMap("select * from vehicle_movement where id=?", id); }
    UUID mechanicOf(UUID id) { return db.queryForObject("select mechanic_user_id from vehicle_movement where id=?", UUID.class, id); }
    long count(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
}
