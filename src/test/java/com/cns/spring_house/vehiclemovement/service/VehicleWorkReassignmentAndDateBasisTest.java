package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
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
 * Follow-up of V46: Police may change WHO is assigned only before work starts,
 * and Admin can ask "what entered" or "what finished" in a period, never mixed.
 * Everything runs through the real HTTP security chain and PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleWorkReassignmentAndDateBasisTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired VehicleWorkService work;
    @Autowired VehicleMovementService movements;
    @Autowired PlatformTransactionManager transactions;
    static final String URL = "/v1/vehicle-movements", REPORT = "/v1/vehicle-work-report";
    /** Since V48 every real change must carry a reason. */
    static final String REASON = "QA: cambio de turno";
    UUID workshop, foreign, police, admin, juan, maria, pedro, foreignOperator, inactive;
    ExecutorService pool;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = TestDatabases.provision("vehicle_work_followup_it");
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
        police = user(workshop, "POLICE", true, "Paula"); admin = user(workshop, "ADMIN", true, "Ana");
        juan = user(workshop, "OPERATOR", true, "Juan"); maria = user(workshop, "OPERATOR", true, "María");
        pedro = user(workshop, "OPERATOR", true, "Pedro"); inactive = user(workshop, "OPERATOR", false, "Inés");
        foreignOperator = user(foreign, "OPERATOR", true, "Otto");
    }
    @AfterEach void stopPool() { pool.shutdownNow(); }

    // ── Requirement A: reassignment before start ─────────────────────────

    @Test void reassignmentBeforeStartChangesOnlyTheMechanicOfTheSameVisit() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var before = row(id);
        long visits = visitCount();
        assign(id, body(maria, juan), police()).andExpect(status().isOk())
            .andExpect(jsonPath("id").value(id.toString()))
            .andExpect(jsonPath("mechanic.id").value(maria.toString()))
            .andExpect(jsonPath("canStartWork").value(true))
            .andExpect(jsonPath("workStartedAt").isEmpty());
        var after = row(id);
        assertThat(changed(before, after)).contains("mechanic_user_id").isSubsetOf("mechanic_user_id", "updated_at");
        assertThat(after.get("mechanic_user_id")).isEqualTo(maria);
        assertThat(visitCount()).isEqualTo(visits);
        http.perform(get(URL + "/" + id).with(police())).andExpect(jsonPath("mechanic.id").value(maria.toString()));

        action(id, "start-work", 200);
        var started = row(id);
        assertThat(started.get("mechanic_user_id")).isEqualTo(maria);
        assign(id, body(pedro, maria), police()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("detail").value(containsString("ya inició")));
        assertThat(row(id)).isEqualTo(started);

        action(id, "finish-work", 200);
        var finished = row(id);
        assign(id, body(pedro, maria), police()).andExpect(status().isBadRequest());
        assertThat(row(id)).isEqualTo(finished);
        assertThat(finished.get("exit")).isNull();
        http.perform(get(REPORT).with(admin())).andExpect(jsonPath("mechanics.length()").value(1))
            .andExpect(jsonPath("mechanics[0].mechanicId").value(maria.toString()))
            .andExpect(jsonPath("mechanics[0].completed").value(1));
    }

    @Test void startedFinishedOrExitedVisitsKeepTheirMechanicForever() throws Exception {
        UUID started = preassigned("P100AAA", juan); action(started, "start-work", 200);
        UUID finished = preassigned("P200BBB", juan); action(finished, "start-work", 200); action(finished, "finish-work", 200);
        UUID exited = preassigned("P300CCC", juan); action(exited, "register-exit", 200);
        UUID done = preassigned("P400DDD", juan);
        action(done, "start-work", 200); action(done, "finish-work", 200); action(done, "register-exit", 200);
        for (UUID id : List.of(started, finished, exited, done)) {
            var before = row(id);
            assign(id, body(maria, juan), police()).andExpect(status().isBadRequest());
            // Even a request that would change nothing is refused once the visit is locked.
            assign(id, body(juan, juan), police()).andExpect(status().isBadRequest());
            assertThat(row(id)).isEqualTo(before);
        }
        assign(exited, body(maria, juan), police()).andExpect(jsonPath("detail").value(containsString("ya salió")));
    }

    @Test void legacyOpenVisitWithoutMechanicCanBeAssignedButClosedOnesAreNeverBackfilled() throws Exception {
        UUID open = legacy("P999ABC", null), closed = legacy("P888ABC", "2026-01-01 09:00:00");
        action(open, "start-work", 400);
        var closedBefore = row(closed);
        assign(closed, body(juan, null), police()).andExpect(status().isBadRequest());
        assertThat(row(closed)).isEqualTo(closedBefore);
        assertThat(closedBefore.get("mechanic_user_id")).isNull();
        assertThat(closedBefore.get("work_started_at")).isNull();
        // V49: the correction path never gives a first mechanic; that happens when work starts.
        var openBefore = row(open);
        assign(open, body(juan, juan), police()).andExpect(status().isBadRequest());
        assign(open, body(juan, null), police()).andExpect(status().isBadRequest());
        assertThat(row(open)).isEqualTo(openBefore);
        // A start dialog that claims a previous mechanic the visit never had is out of date.
        start(open, juan, juan, null).andExpect(status().isConflict());
        assertThat(row(open)).isEqualTo(openBefore);
        start(open, juan, null, null).andExpect(status().isOk())
            .andExpect(jsonPath("mechanic.id").value(juan.toString())).andExpect(jsonPath("work.state").value("ACTIVE"));
        assertThat(changed(openBefore, row(open))).isSubsetOf("mechanic_user_id", "updated_at", "work_started_at", "started_by");
        assertThat(row(open).get("mechanic_user_id")).isEqualTo(juan);
    }

    @Test void replacementMustBeAnActiveOperatorOfTheVisitWorkshopAndBodyCarriesNoTimes() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var before = row(id);
        for (UUID invalid : List.of(foreignOperator, inactive, police, admin, UUID.randomUUID()))
            assign(id, body(invalid, juan), police()).andExpect(status().isBadRequest());
        assign(id, "{\"expectedMechanicId\":\"" + juan + "\"}", police()).andExpect(status().isBadRequest());
        assign(id, "{\"mechanicId\":\"" + maria + "\",\"expectedMechanicId\":\"" + juan
            + "\",\"workStartedAt\":\"2020-01-01T00:00:00Z\"}", police()).andExpect(status().isBadRequest())
            .andExpect(jsonPath("detail").value(containsString("fija el servidor")));
        assign(id, "{\"mechanicId\":\"" + foreignOperator + "\",\"expectedMechanicId\":\"" + juan
            + "\",\"workshopId\":\"" + foreign + "\"}", police()).andExpect(status().isBadRequest());
        assign(id, "", police()).andExpect(status().isBadRequest());
        assign(id, "not-json", police()).andExpect(status().isBadRequest());
        http.perform(patch(URL + "/" + id + "/mechanic").with(police())).andExpect(status().isBadRequest());
        assertThat(row(id)).isEqualTo(before);
        var offered = json.readTree(http.perform(get(URL + "/mechanics").with(police()))
            .andReturn().getResponse().getContentAsString());
        assertThat(values(offered, "id")).containsExactlyInAnyOrder(juan.toString(), maria.toString(), pedro.toString());
    }

    @Test void onlyPoliceOfTheVisitWorkshopMayReassign() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var before = row(id);
        for (String role : List.of("ADMIN", "OPERATOR", "SELLER", "CONTROL", "SECRETARY"))
            assign(id, body(maria, juan), auth(admin, workshop, role)).andExpect(status().isForbidden());
        UUID outsider = user(foreign, "POLICE", true, "Fausto");
        assign(id, body(maria, juan), auth(outsider, foreign, "POLICE")).andExpect(status().isNotFound());
        assign(id, body(foreignOperator, juan), auth(outsider, foreign, "POLICE")).andExpect(status().isNotFound());
        http.perform(patch(URL + "/" + id + "/mechanic").contentType(MediaType.APPLICATION_JSON).content(body(maria, juan)))
            .andExpect(status().isUnauthorized());
        assign(UUID.randomUUID(), body(maria, juan), police()).andExpect(status().isNotFound());
        assertThat(row(id)).isEqualTo(before);
    }

    @Test void staleDialogsConflictAndRepeatedRequestsDoNotWriteTwice() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        assign(id, body(maria, juan), police()).andExpect(status().isOk());
        var afterFirst = row(id);
        // A retry whose response was lost, or a second session that chose the same person: no write.
        assign(id, body(maria, juan), police()).andExpect(status().isOk())
            .andExpect(jsonPath("mechanic.id").value(maria.toString()));
        assertThat(row(id)).isEqualTo(afterFirst);
        // A dialog still showing Juan tries Pedro: its before/after confirmation would be false.
        assign(id, body(pedro, juan), police()).andExpect(status().isConflict())
            .andExpect(jsonPath("detail").value(containsString("Otra sesión")));
        assign(id, "{\"mechanicId\":\"" + pedro + "\"}", police()).andExpect(status().isConflict());
        assertThat(row(id)).isEqualTo(afterFirst);
    }

    // ── Requirement A: real overlapping transactions ─────────────────────

    @Test void correctionCommittedFirstMakesTheStaleStartConflictWithoutWriting() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var held = holdTransaction(() -> work.assignMechanic(id, new AssignMechanicRequestDTO(maria, juan, REASON, null)));
        // The start dialog was opened while Juan was assigned and starts with him.
        Future<MvcResult> start = pool.submit(() -> start(id, juan, juan, null).andReturn());
        awaitLockWait();
        assertThat(start.isDone()).isFalse();
        held.release().countDown();
        assertThat(held.result().get(20, TimeUnit.SECONDS).mechanic().id()).isEqualTo(maria);
        var response = start.get(20, TimeUnit.SECONDS).getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        var r = row(id);
        assertThat(r.get("mechanic_user_id")).isEqualTo(maria);
        assertThat(r.get("work_started_at")).isNull();
        assertThat(db.queryForObject("select count(*) from vehicle_work_interval where vehicle_movement_id=?", Long.class, id)).isZero();
        // Re-read and start with the real assignment: now it succeeds.
        start(id, maria, maria, null).andExpect(status().isOk());
        assertThat(row(id).get("started_by")).isEqualTo(police);
    }

    @Test void startCommittedFirstMakesTheWaitingReassignmentFail() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        var held = holdTransaction(() -> work.start(id, new StartWorkRequestDTO(juan, juan, null, null)));
        Future<MvcResult> reassign = pool.submit(() -> assign(id, body(maria, juan), police()).andReturn());
        awaitLockWait();
        assertThat(reassign.isDone()).isFalse();
        held.release().countDown();
        var startedAt = held.result().get(20, TimeUnit.SECONDS).workStartedAt();
        assertThat(reassign.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        var r = row(id);
        assertThat(r.get("mechanic_user_id")).isEqualTo(juan);
        assertThat(((java.sql.Timestamp) r.get("work_started_at")).toInstant()).isEqualTo(startedAt);
    }

    @Test void finishOrExitCommittedFirstMakesTheWaitingReassignmentFail() throws Exception {
        UUID leaving = preassigned("P500EEE", juan);
        var heldExit = holdTransaction(() -> movements.registerExit(leaving));
        Future<MvcResult> late = pool.submit(() -> assign(leaving, body(maria, juan), police()).andReturn());
        awaitLockWait();
        heldExit.release().countDown();
        heldExit.result().get(20, TimeUnit.SECONDS);
        assertThat(late.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        assertThat(row(leaving).get("mechanic_user_id")).isEqualTo(juan);

        UUID finishing = preassigned("P600FFF", juan);
        action(finishing, "start-work", 200);
        var heldFinish = holdTransaction(() -> work.finish(finishing));
        Future<MvcResult> stale = pool.submit(() -> assign(finishing, body(maria, juan), police()).andReturn());
        awaitLockWait();
        heldFinish.release().countDown();
        heldFinish.result().get(20, TimeUnit.SECONDS);
        assertThat(stale.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        assertThat(row(finishing).get("mechanic_user_id")).isEqualTo(juan);
    }

    @Test void concurrentDeactivationNeverYieldsAnIneligibleReplacementNorATrappedVisit() throws Exception {
        UUID id = preassigned("P123ABC", juan);
        // Deactivation commits first: the waiting reassignment re-reads María as inactive and writes nothing.
        var deactivation = holdTransaction(() -> db.update("update user_account set is_active=false where id=?", maria));
        Future<MvcResult> reassign = pool.submit(() -> assign(id, body(maria, juan), police()).andReturn());
        awaitLockWait();
        assertThat(reassign.isDone()).isFalse();
        deactivation.release().countDown();
        deactivation.result().get(20, TimeUnit.SECONDS);
        assertThat(reassign.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        assertThat(row(id).get("mechanic_user_id")).isEqualTo(juan);

        // Reassignment commits first: the deactivation waits for its FOR SHARE lock...
        var assignment = holdTransaction(() -> work.assignMechanic(id, new AssignMechanicRequestDTO(pedro, juan, REASON, null)));
        Future<Integer> deactivate = pool.submit(() -> db.update("update user_account set is_active=false where id=?", pedro));
        awaitLockWait();
        assertThat(deactivate.isDone()).isFalse();
        assignment.release().countDown();
        assignment.result().get(20, TimeUnit.SECONDS);
        deactivate.get(20, TimeUnit.SECONDS);
        // ...so the visit names someone who left afterwards. Start is refused but the visit is not trapped.
        http.perform(get(URL + "/" + id).with(police())).andExpect(jsonPath("mechanic.id").value(pedro.toString()))
            .andExpect(jsonPath("canStartWork").value(true)).andExpect(jsonPath("mechanicEligible").value(false));
        action(id, "start-work", 400);
        UUID rosa = user(workshop, "OPERATOR", true, "Rosa");
        assign(id, body(rosa, pedro), police()).andExpect(status().isOk()).andExpect(jsonPath("canStartWork").value(true));
        action(id, "start-work", 200);
        assertThat(row(id).get("mechanic_user_id")).isEqualTo(rosa);
    }

    // ── Requirement B: ENTRY vs FINISHED date basis ──────────────────────

    @Test void crossMonthVisitBelongsToItsEntryCohortOrItsFinishCohortNeverBoth() throws Exception {
        UUID crossMonth = visit("P123ABC", juan, "2026-10-30 10:00:00", "2026-11-02 12:00:00",
            "2026-10-31T15:00:00Z", "2026-11-02T15:00:00Z");                               // 172800 s
        UUID pending = visit("P123ABC", maria, "2026-11-02 09:00:00", null, null, null);   // same plate, other mechanic
        UUID quick = visit("P222BBB", juan, "2026-11-10 08:00:00", "2026-11-10 12:00:00",
            "2026-11-10T15:00:00Z", "2026-11-10T15:01:00Z");                               // 60 s
        UUID slow = visit("P123ABC", maria, "2026-11-01 07:00:00", "2026-11-01 09:00:00",
            "2026-11-11T15:00:00Z", "2026-11-11T15:03:00Z");                               // 180 s
        UUID running = visit("P333CCC", juan, "2026-11-12 08:00:00", null, "2026-11-12T15:00:00Z", null);

        var byEntry = report("dateBasis", "ENTRY", "from", "2026-11-01", "to", "2026-11-30");
        assertThat(byEntry.get("dateBasis").asString()).isEqualTo("ENTRY");
        assertThat(jobIds(byEntry)).containsExactlyInAnyOrder(ids(pending, quick, slow, running));
        assertSummary(byEntry, 2, 1, 1, "240", "120");
        assertMechanic(byEntry, juan, 1, 1, 0, "60");
        assertMechanic(byEntry, maria, 1, 0, 1, "180");

        var byFinish = report("dateBasis", "FINISHED", "from", "2026-11-01", "to", "2026-11-30");
        assertThat(byFinish.get("dateBasis").asString()).isEqualTo("FINISHED");
        assertThat(jobIds(byFinish)).containsExactly(ids(slow, quick, crossMonth)); // most recent finish first
        assertSummary(byFinish, 3, 0, 0, "173040", "57680");
        assertMechanic(byFinish, juan, 2, 0, 0, "86430");
        assertMechanic(byFinish, maria, 1, 0, 0, "180");
        assertConsistent(byFinish);
        assertConsistent(byEntry);

        assertThat(jobIds(report("dateBasis", "ENTRY", "from", "2026-10-01", "to", "2026-10-31"))).containsExactly(ids(crossMonth));
        var octoberFinish = report("dateBasis", "FINISHED", "from", "2026-10-01", "to", "2026-10-31");
        assertThat(octoberFinish.get("total").asLong()).isZero();
        assertSummary(octoberFinish, 0, 0, 0, "0", null);
        assertThat(octoberFinish.get("mechanics").size()).isZero();
        assertSummary(report("dateBasis", "FINISHED", "from", "2026-11-10", "to", "2026-11-11"), 2, 0, 0, "240", "120");

        // The same plate on separate visits stays separate rows; nothing is collapsed by plate.
        assertThat(values(byFinish.get("jobs"), "plate")).containsExactly("P123ABC", "P222BBB", "P123ABC");

        // Deactivating Juan and transferring María changes nothing about their historical attribution.
        db.update("update user_account set is_active=false where id=?", juan);
        db.update("update employee set workshop_id=? where id=(select employee_id from user_account where id=?)", foreign, maria);
        var later = report("dateBasis", "FINISHED", "from", "2026-11-01", "to", "2026-11-30");
        assertSummary(later, 3, 0, 0, "173040", "57680");
        assertMechanic(later, juan, 2, 0, 0, "86430");
        assertMechanic(later, maria, 1, 0, 0, "180");
    }

    @Test void finishFilterUsesGuatemalaCalendarDaysWithAnExclusiveEnd() throws Exception {
        UUID novFirstMidnight = finishedAt("P001AAA", "2026-11-01T06:00:00Z");     // 00:00 GT, 1 Nov
        UUID octLastInstant = finishedAt("P002AAA", "2026-11-01T05:59:59.999999Z"); // 23:59:59.999999 GT, 31 Oct
        UUID novLastLate = finishedAt("P003AAA", "2026-12-01T05:30:00Z");          // 23:30 GT, 30 Nov (UTC says 1 Dec)
        UUID decFirstMidnight = finishedAt("P004AAA", "2026-12-01T06:00:00Z");     // 00:00 GT, 1 Dec
        assertThat(jobIds(report("dateBasis", "FINISHED", "from", "2026-11-01", "to", "2026-11-30")))
            .containsExactlyInAnyOrder(ids(novFirstMidnight, novLastLate));
        assertThat(jobIds(report("dateBasis", "FINISHED", "from", "2026-10-31", "to", "2026-10-31"))).containsExactly(ids(octLastInstant));
        assertThat(jobIds(report("dateBasis", "FINISHED", "from", "2026-11-30", "to", "2026-11-30"))).containsExactly(ids(novLastLate));
        assertThat(jobIds(report("dateBasis", "FINISHED", "from", "2026-12-01"))).containsExactly(ids(decFirstMidnight));
        assertThat(jobIds(report("dateBasis", "FINISHED", "to", "2026-10-31"))).containsExactly(ids(octLastInstant));

        // ENTRY keeps its accepted inclusive Guatemala-day semantics on the LocalDateTime column.
        UUID enteredAtMidnight = visit("P005AAA", juan, "2026-11-01 00:00:00", null, null, null);
        UUID enteredJustBefore = visit("P006AAA", juan, "2026-10-31 23:59:59.999999", null, null, null);
        var entries = jobIds(report("dateBasis", "ENTRY", "from", "2026-11-01", "to", "2026-11-01"));
        assertThat(entries).contains(enteredAtMidnight.toString()).doesNotContain(enteredJustBefore.toString());
    }

    @Test void dateBasisIsValidatedDefaultsToEntryAndRejectsImpossibleStates() throws Exception {
        visit("P123ABC", juan, "2026-11-02 09:00:00", null, null, null);
        finishedAt("P222BBB", "2026-11-03T15:00:00Z");
        var implicit = report("from", "2026-11-01", "to", "2026-11-30");
        var explicit = report("dateBasis", "ENTRY", "from", "2026-11-01", "to", "2026-11-30");
        assertThat(implicit.get("dateBasis").asString()).isEqualTo("ENTRY");
        // Identical answers; only serverTime (the instant of each read) may differ.
        ((tools.jackson.databind.node.ObjectNode) implicit).remove("serverTime");
        ((tools.jackson.databind.node.ObjectNode) explicit).remove("serverTime");
        assertThat(implicit.toString()).isEqualTo(explicit.toString());
        for (String bad : List.of("EXIT", "entry", "STARTED", "'; drop table vehicle_movement; --"))
            http.perform(get(REPORT).param("dateBasis", bad).with(admin())).andExpect(status().isBadRequest());
        for (String state : List.of("NOT_STARTED", "IN_PROGRESS"))
            http.perform(get(REPORT).param("dateBasis", "FINISHED").param("state", state).with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value(containsString("solo hay trabajos finalizados")));
        assertThat(report("dateBasis", "FINISHED", "state", "FINISHED").get("total").asLong()).isEqualTo(1);
        assertThat(report("dateBasis", "FINISHED").get("summary").get("notStarted").asLong()).isZero();
        http.perform(get(REPORT).param("dateBasis", "FINISHED").param("from", "2026-11-02").param("to", "2026-11-01")
            .with(admin())).andExpect(status().isBadRequest());
        http.perform(get(REPORT).param("dateBasis", "FINISHED").param("from", "02/11/2026").with(admin()))
            .andExpect(status().isBadRequest());
        http.perform(get(REPORT).param("dateBasis", "FINISHED").param("size", "101").with(admin())).andExpect(status().isBadRequest());
        http.perform(get(REPORT).param("dateBasis", "FINISHED").with(police())).andExpect(status().isForbidden());
        // Another workshop's Admin never sees these visits under either basis.
        UUID foreignAdmin = user(foreign, "ADMIN", true, "Fabiola");
        for (String basis : List.of("ENTRY", "FINISHED"))
            assertThat(json.readTree(http.perform(get(REPORT).param("dateBasis", basis).with(auth(foreignAdmin, foreign, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("total").asLong()).isZero();
    }

    @Test void summaryGroupsAndEveryPageAgreeUnderBothBases() throws Exception {
        for (int i = 0; i < 3; i++)
            visit("P10" + i + "AAA", juan, "2026-11-0" + (i + 1) + " 08:00:00", "2026-11-0" + (i + 1) + " 12:00:00",
                "2026-11-0" + (i + 1) + "T15:00:00Z", "2026-11-0" + (i + 1) + "T15:0" + (i + 1) + ":00Z");
        visit("P200AAA", maria, "2026-11-04 08:00:00", "2026-11-04 12:00:00", "2026-11-04T15:00:00Z", "2026-11-04T15:10:00Z");
        visit("P300AAA", maria, "2026-11-05 08:00:00", null, null, null);
        for (String basis : List.of("ENTRY", "FINISHED")) {
            var whole = report("dateBasis", basis, "from", "2026-11-01", "to", "2026-11-30");
            assertConsistent(whole);
            long total = whole.get("total").asLong();
            assertThat(total).isEqualTo(basis.equals("ENTRY") ? 5 : 4);
            var seen = new LinkedHashSet<String>();
            for (int page = 0; page <= total; page++) {
                var slice = report("dateBasis", basis, "from", "2026-11-01", "to", "2026-11-30", "size", "2", "page", "" + page);
                assertThat(slice.get("summary").toString()).isEqualTo(whole.get("summary").toString());
                assertThat(slice.get("total").asLong()).isEqualTo(total);
                seen.addAll(jobIds(slice));
            }
            assertThat(seen).hasSize((int) total).containsExactlyInAnyOrderElementsOf(jobIds(whole));
            var groups = new LinkedHashSet<String>();
            for (int page = 0; page < 3; page++)
                groups.addAll(values(report("dateBasis", basis, "from", "2026-11-01", "to", "2026-11-30", "size", "1",
                    "mechanicPage", "" + page).get("mechanics"), "mechanicId"));
            assertThat(groups).containsExactlyInAnyOrder(juan.toString(), maria.toString());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    record Held<T>(Future<T> result, CountDownLatch release) {}

    /** Runs {@code work} as Police in a transaction that stays open, holding its locks, until released. */
    <T> Held<T> holdTransaction(Supplier<T> work) throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var template = new TransactionTemplate(transactions);
        Future<T> result = pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(token(police, workshop, "POLICE"));
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

    /** Proves the contender really overlaps: PostgreSQL shows it waiting on the holder's row lock. */
    void awaitLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (db.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                + "and wait_event_type='Lock'", Long.class) == 0) {
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

    UUID visit(String plate, UUID mechanic, String entry, String exit, String started, String finished) {
        UUID id = UUID.randomUUID();
        db.update("insert into vehicle_movement(id,workshop_id,plate,entry,exit,mechanic_user_id,work_started_at,work_finished_at,"
            + "created_at,updated_at) values(?,?,?,?::timestamp,?::timestamp,?,?::timestamptz,?::timestamptz,now(),now())",
            id, workshop, plate, entry, exit, mechanic, started, finished);
        return id;
    }

    UUID finishedAt(String plate, String finished) {
        UUID id = visit(plate, juan, "2026-10-15 08:00:00", null, null, null);
        db.update("update vehicle_movement set work_started_at=?::timestamptz - interval '1 minute', work_finished_at=?::timestamptz "
            + "where id=?", finished, finished, id);
        return id;
    }

    /** start-work conserva al mecanico vigente (asignacion previa historica); el resto va sin cuerpo. */
    void action(UUID id, String action, int status) throws Exception {
        var request = post(URL + "/" + id + "/" + action).with(police());
        if (action.equals("start-work"))
            request.contentType(MediaType.APPLICATION_JSON).content(LegacyVisits.keepCurrentMechanic(db, id));
        http.perform(request).andExpect(status().is(status));
    }

    ResultActions start(UUID id, UUID mechanic, UUID expected, String reason) throws Exception {
        return http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content("{\"mechanicId\":" + quoted(mechanic) + ",\"expectedMechanicId\":" + quoted(expected)
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}").with(police()));
    }

    ResultActions assign(UUID id, String body, RequestPostProcessor who) throws Exception {
        return http.perform(patch(URL + "/" + id + "/mechanic").contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    String body(UUID mechanic, UUID expected) {
        return "{\"mechanicId\":" + quoted(mechanic) + ",\"expectedMechanicId\":" + quoted(expected)
            + ",\"reason\":\"" + REASON + "\"}";
    }
    String quoted(UUID id) { return id == null ? "null" : "\"" + id + "\""; }

    Map<String, Object> row(UUID id) { return db.queryForMap("select * from vehicle_movement where id=?", id); }
    long visitCount() { return db.queryForObject("select count(*) from vehicle_movement where workshop_id=?", Long.class, workshop); }

    Set<String> changed(Map<String, Object> before, Map<String, Object> after) {
        var keys = new TreeSet<String>();
        before.forEach((key, value) -> { if (!Objects.equals(value, after.get(key))) keys.add(key); });
        return keys;
    }

    JsonNode report(String... params) throws Exception {
        var request = get(REPORT).with(admin());
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return json.readTree(http.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    List<String> values(JsonNode array, String field) {
        var out = new ArrayList<String>();
        for (int i = 0; i < array.size(); i++) out.add(array.get(i).get(field).asString());
        return out;
    }
    List<String> jobIds(JsonNode report) { return values(report.get("jobs"), "id"); }
    String[] ids(UUID... ids) { return Arrays.stream(ids).map(UUID::toString).toArray(String[]::new); }

    void assertSummary(JsonNode report, long completed, long inProgress, long notStarted, String total, String average) {
        var s = report.get("summary");
        assertThat(s.get("completed").asLong()).isEqualTo(completed);
        assertThat(s.get("inProgress").asLong()).isEqualTo(inProgress);
        assertThat(s.get("notStarted").asLong()).isEqualTo(notStarted);
        assertThat(s.get("totalSeconds").decimalValue()).isEqualByComparingTo(total);
        assertAverage(s.get("averageSeconds"), average);
    }

    void assertMechanic(JsonNode report, UUID mechanic, long completed, long inProgress, long notStarted, String average) {
        JsonNode m = null;
        for (int i = 0; i < report.get("mechanics").size(); i++)
            if (mechanic.toString().equals(report.get("mechanics").get(i).get("mechanicId").asString())) m = report.get("mechanics").get(i);
        assertThat(m).as("group for " + mechanic).isNotNull();
        assertThat(m.get("completed").asLong()).isEqualTo(completed);
        assertThat(m.get("inProgress").asLong()).isEqualTo(inProgress);
        assertThat(m.get("notStarted").asLong()).isEqualTo(notStarted);
        assertAverage(m.get("averageSeconds"), average);
    }

    void assertAverage(JsonNode value, String expected) {
        if (expected == null) assertThat(value == null || value.isNull() || value.isMissingNode()).isTrue();
        else assertThat(value.decimalValue()).isEqualByComparingTo(expected);
    }

    /** Groups add up to the summary, and the summary adds up to the visit count of the same cohort. */
    void assertConsistent(JsonNode report) {
        var s = report.get("summary");
        long completed = 0, inProgress = 0, notStarted = 0;
        BigDecimal seconds = BigDecimal.ZERO;
        var groups = report.get("mechanics");
        assertThat(report.get("mechanicCount").asLong()).isEqualTo(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            completed += groups.get(i).get("completed").asLong();
            inProgress += groups.get(i).get("inProgress").asLong();
            notStarted += groups.get(i).get("notStarted").asLong();
            seconds = seconds.add(groups.get(i).get("totalSeconds").decimalValue());
        }
        assertThat(completed).isEqualTo(s.get("completed").asLong());
        assertThat(inProgress).isEqualTo(s.get("inProgress").asLong());
        assertThat(notStarted).isEqualTo(s.get("notStarted").asLong());
        assertThat(seconds).isEqualByComparingTo(s.get("totalSeconds").decimalValue());
        assertThat(completed + inProgress + notStarted).isEqualTo(report.get("total").asLong());
    }
}
