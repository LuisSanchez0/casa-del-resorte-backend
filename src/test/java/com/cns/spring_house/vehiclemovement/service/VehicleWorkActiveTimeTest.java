package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
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
import com.cns.spring_house.vehiclemovement.dto.StartWorkRequestDTO;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V49: the mechanic is chosen when work STARTS, and only ACTIVE time counts.
 * Everything goes through the real HTTP security chain, the real services and
 * PostgreSQL; only the application {@link Clock} is pinned so that exact
 * Guatemala wall-clock scenarios (10:00 → 12:40) can be replayed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VehicleWorkActiveTimeTest {
    static final ZoneId GT = ZoneId.of("America/Guatemala");
    static final String URL = "/v1/vehicle-movements", REPORT = "/v1/vehicle-work-report";
    /** Work days well after the real admission instant (entry uses the real clock). */
    static final String DAY = "2026-12-01";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired VehicleWorkService work;
    @Autowired com.cns.spring_house.vehiclemovement.service.VehicleMovementService movements;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    UUID workshop, foreign, police, police2, admin, maria, juan, pedro, inactive, foreignOperator;
    ExecutorService pool;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = TestDatabases.provision("vehicle_work_active_time_it");
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", TestDatabases::username);
        r.add("spring.datasource.password", TestDatabases::password);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> true);
    }

    /** A settable application clock (the services read time only through it). */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-12-01T16:00:00Z");
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return GT; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class PinnedClock {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    @BeforeEach void setup() {
        pool = Executors.newCachedThreadPool();
        workshop = shop("QA A"); foreign = shop("QA B");
        police = user(workshop, "POLICE", true, "Paula", "Agente"); police2 = user(workshop, "POLICE", true, "Pablo", "Agente");
        admin = user(workshop, "ADMIN", true, "Ana", "Admin");
        maria = user(workshop, "OPERATOR", true, "María", "López"); juan = user(workshop, "OPERATOR", true, "Juan", "Pérez");
        pedro = user(workshop, "OPERATOR", true, "Pedro", "Gómez"); inactive = user(workshop, "OPERATOR", false, "Inés", "Baja");
        foreignOperator = user(foreign, "OPERATOR", true, "Otto", "Ajeno");
        at(DAY, "10:00");
    }
    @AfterEach void stopPool() { pool.shutdownNow(); }

    // ── The accepted example: 25 + 35 + 40 = 100 min active, 60 min paused, 160 min elapsed ──

    @Test void twoPausesAndTwoResumesCountOnlyActiveTimeExactly() throws Exception {
        UUID a = admitted("P123ABC");
        at(DAY, "10:00"); var started = ok(start(a, maria, null, null));
        assertWork(started, "ACTIVE", "MEASURED", "0", "0", "0");
        assertThat(started.get("mechanic").get("id").asString()).isEqualTo(maria.toString());
        assertThat(started.get("work").get("activeSince").asString()).isEqualTo("2026-12-01T16:00:00Z");

        at(DAY, "10:25"); var paused = ok(act(a, "pause-work"));
        assertWork(paused, "PAUSED", "MEASURED", "1500", "0", "1500");
        assertThat(paused.get("work").get("pausedSince").asString()).isEqualTo("2026-12-01T16:25:00Z");
        at(DAY, "11:00"); // a reload while paused: active frozen, the pause grows
        assertWork(read(a), "PAUSED", "MEASURED", "1500", "2100", "3600");
        at(DAY, "11:10"); assertWork(ok(act(a, "resume-work")), "ACTIVE", "MEASURED", "1500", "2700", "4200");
        at(DAY, "11:30"); assertWork(read(a), "ACTIVE", "MEASURED", "2700", "2700", "5400");
        at(DAY, "11:45"); assertWork(ok(act(a, "pause-work")), "PAUSED", "MEASURED", "3600", "2700", "6300");
        at(DAY, "12:00"); assertWork(ok(act(a, "resume-work")), "ACTIVE", "MEASURED", "3600", "3600", "7200");
        at(DAY, "12:40"); var finished = ok(act(a, "finish-work"));
        assertWork(finished, "FINISHED", "MEASURED", "6000", "3600", "9600");
        assertThat(finished.get("work").get("workSeconds").decimalValue()).isEqualByComparingTo("6000");
        assertThat(finished.get("exit").isNull()).isTrue();
        at(DAY, "18:00"); // final and immutable afterwards
        assertWork(read(a), "FINISHED", "MEASURED", "6000", "3600", "9600");

        var visit = row(a);
        assertThat(((Timestamp) visit.get("work_started_at")).toInstant()).isEqualTo(gt(DAY, "10:00"));
        assertThat(((Timestamp) visit.get("work_finished_at")).toInstant()).isEqualTo(gt(DAY, "12:40"));
        assertThat(visit.get("exit")).isNull();
        assertThat(intervals(a)).extracting(i -> i.get("origin") + " " + inst(i.get("started_at")) + "→" + inst(i.get("ended_at")))
            .containsExactly("START 2026-12-01T16:00:00Z→2026-12-01T16:25:00Z",
                "RESUME 2026-12-01T17:10:00Z→2026-12-01T17:45:00Z",
                "RESUME 2026-12-01T18:00:00Z→2026-12-01T18:40:00Z");
        assertThat(intervals(a)).allSatisfy(i -> assertThat(i.get("started_by_user_id")).isEqualTo(police));
        // Pause/resume are not mechanic events: exactly one INITIAL_ASSIGNMENT exists.
        assertThat(eventTypes(a)).containsExactly("INITIAL_ASSIGNMENT");

        // Visit B — 2 h 20 min with no pauses — for the same mechanic.
        UUID b = admitted("P222BBB");
        at(DAY, "13:00"); ok(start(b, maria, null, null));
        at(DAY, "15:20"); assertWork(ok(act(b, "finish-work")), "FINISHED", "MEASURED", "8400", "0", "8400");

        for (String basis : List.of("ENTRY", "FINISHED")) {
            var report = report("dateBasis", basis);
            var s = report.get("summary");
            assertThat(s.get("completed").asLong()).isEqualTo(2);
            assertDecimal(s, "totalSeconds", "14400");      // 4 h ACTIVE
            assertDecimal(s, "averageSeconds", "7200");     // 2 h ACTIVE
            assertThat(s.get("legacyCompleted").asLong()).isZero();
            assertThat(s.get("measuredCompleted").asLong()).isEqualTo(2);
            assertDecimal(s, "measuredActiveSeconds", "14400");
            assertDecimal(s, "measuredAverageSeconds", "7200");
            assertDecimal(s, "pausedSeconds", "3600");
            assertDecimal(s, "elapsedSeconds", "18000");    // 2 h 40 + 2 h 20, never the productivity metric
            var group = report.get("mechanics").get(0);
            assertThat(group.get("mechanicId").asString()).isEqualTo(maria.toString());
            assertDecimal(group, "averageSeconds", "7200");
            var job = jobs(report).get(a.toString());
            assertThat(job.get("workState").asString()).isEqualTo("FINISHED");
            assertThat(job.get("timingBasis").asString()).isEqualTo("MEASURED");
            assertDecimal(job, "activeSeconds", "6000"); assertDecimal(job, "pausedSeconds", "3600");
            assertDecimal(job, "elapsedSeconds", "9600"); assertDecimal(job, "workSeconds", "6000");
        }
        var detail = json(http.perform(get(REPORT + "/" + a).with(admin())).andExpect(status().isOk()));
        assertDecimal(detail, "activeSeconds", "6000");
    }

    @Test void finishingFromPauseAddsNoPauseAndFinishFromActiveClosesAtTheFinishInstant() throws Exception {
        UUID fromPause = admitted("P100AAA");
        at(DAY, "08:00"); ok(start(fromPause, juan, null, null));
        at(DAY, "08:10"); ok(act(fromPause, "pause-work"));
        at(DAY, "09:00"); var done = ok(act(fromPause, "finish-work"));
        assertWork(done, "FINISHED", "MEASURED", "600", "3000", "3600");
        assertThat(intervals(fromPause)).hasSize(1);
        assertThat(inst(intervals(fromPause).get(0).get("ended_at"))).isEqualTo("2026-12-01T14:10:00Z");

        UUID fromActive = admitted("P200AAA");
        at(DAY, "08:00"); ok(start(fromActive, juan, null, null));
        at(DAY, "08:45"); assertWork(ok(act(fromActive, "finish-work")), "FINISHED", "MEASURED", "2700", "0", "2700");
        var only = intervals(fromActive).get(0);
        assertThat(only.get("ended_at")).isEqualTo(row(fromActive).get("work_finished_at"));
        assertThat(only.get("ended_by_user_id")).isEqualTo(police);
        assertThat(row(fromActive).get("finished_by")).isEqualTo(police);
    }

    @Test void zeroSecondStretchesAndMidnightUseGuatemalaDaysAndExactMicroseconds() throws Exception {
        UUID id = admitted("P300AAA");
        at(DAY, "23:50"); ok(start(id, pedro, null, null));
        ok(act(id, "pause-work"));                      // zero-second ACTIVE stretch is legitimate
        ok(act(id, "resume-work"));                     // zero-second pause, no overlap
        clock.set(gt("2026-12-02", "00:20").plusNanos(123_456_000)); // crosses Guatemala midnight
        var done = ok(act(id, "finish-work"));
        assertWork(done, "FINISHED", "MEASURED", "1800.123456", "0", "1800.123456");
        assertThat(intervals(id)).hasSize(2);
        // The finish date is 2 Dec in Guatemala (06:20 UTC): only that day finds it.
        assertThat(jobs(report("dateBasis", "FINISHED", "from", "2026-12-02", "to", "2026-12-02"))).containsKey(id.toString());
        assertThat(jobs(report("dateBasis", "FINISHED", "from", DAY, "to", DAY))).doesNotContainKey(id.toString());
    }

    // ── Invalid transitions never write ────────────────────────────────────

    @Test void everyInvalidTransitionIsRefusedWithoutWriting() throws Exception {
        UUID id = admitted("P400AAA");
        for (String action : List.of("pause-work", "resume-work", "finish-work")) expectUnchanged(id, () -> act(id, action), 400);
        ok(start(id, maria, null, null));
        expectUnchanged(id, () -> act(id, "resume-work"), 400);                 // already active
        expectUnchanged(id, () -> start(id, maria, maria, null), 400);           // start twice
        expectUnchanged(id, () -> act(id, "register-exit"), 400);               // exit while ACTIVE
        at(DAY, "10:05"); ok(act(id, "pause-work"));
        expectUnchanged(id, () -> act(id, "pause-work"), 400);                  // pause twice
        expectUnchanged(id, () -> act(id, "register-exit"), 400);               // exit while PAUSED
        // The mechanic is locked while paused, through every path.
        expectUnchanged(id, () -> http.perform(patch(URL + "/" + id + "/mechanic").contentType(MediaType.APPLICATION_JSON)
            .content("{\"mechanicId\":\"" + juan + "\",\"expectedMechanicId\":\"" + maria + "\",\"reason\":\"x\"}").with(police())), 400);
        for (String body : List.of("{\"at\":\"2020-01-01T00:00:00Z\"}", "{\"activeSeconds\":1}"))
            for (String action : List.of("pause-work", "resume-work", "finish-work"))
                expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON)
                    .content(body).with(police())), 400);
        at(DAY, "10:30"); ok(act(id, "finish-work"));
        for (String action : List.of("pause-work", "resume-work", "finish-work")) expectUnchanged(id, () -> act(id, action), 400);
        expectUnchanged(id, () -> start(id, maria, maria, null), 400);
        ok(act(id, "register-exit"));
        for (String action : List.of("pause-work", "resume-work", "finish-work")) expectUnchanged(id, () -> act(id, action), 400);
        assertThat(intervals(id)).hasSize(1);
    }

    // ── Atomic start with mechanic, V47/V48 semantics ──────────────────────

    @Test void startRefusesInvalidMechanicsAndStaleDialogsWithZeroWrites() throws Exception {
        UUID id = admitted("P500AAA");
        expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/start-work").with(police())), 400);
        for (UUID invalid : List.of(foreignOperator, inactive, police, admin, UUID.randomUUID()))
            expectUnchanged(id, () -> start(id, invalid, null, null), 400);
        expectUnchanged(id, () -> start(id, maria, juan, null), 409);            // dialog believed Juan was assigned
        expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content("{\"mechanicId\":\"" + maria + "\",\"workStartedAt\":\"2020-01-01T00:00:00Z\"}").with(police())), 400);
        expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content("{\"mechanicId\":\"" + maria + "\",\"workshopId\":\"" + foreign + "\"}").with(police())), 400);
        expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content("not-json").with(police())), 400);
        assertThat(row(id).get("mechanic_user_id")).isNull();
        var started = ok(start(id, maria, null, "ignorado: primera asignación"));
        assertThat(started.get("mechanic").get("id").asString()).isEqualTo(maria.toString());
        var event = events(id).get(0);
        assertThat(event).containsEntry("event_type", "INITIAL_ASSIGNMENT").containsEntry("new_mechanic_name_at_event", "María López")
            .containsEntry("actor_name_at_event", "Paula Agente");
        assertThat(event.get("reassignment_reason")).isNull();
        assertThat(event.get("changed_at")).isEqualTo(row(id).get("work_started_at"));
        assertThat(intervals(id).get(0).get("started_at")).isEqualTo(row(id).get("work_started_at"));
    }

    @Test void legacyPreassignedVisitsKeepTheirHistoryAndChangeOnlyWithAReason() throws Exception {
        UUID same = admitted("P600AAA"); LegacyVisits.preassign(db, same, juan, police);
        ok(start(same, juan, juan, null));
        assertThat(eventTypes(same)).containsExactly("INITIAL_ASSIGNMENT");   // no duplicate initial event

        UUID changed = admitted("P700AAA"); LegacyVisits.preassign(db, changed, juan, police);
        var before = events(changed);
        for (String reason : Arrays.asList(null, "", "   ", "x".repeat(301)))
            expectUnchanged(changed, () -> start(changed, maria, juan, reason), 400);
        assertThat(events(changed)).isEqualTo(before);
        ok(start(changed, maria, juan, "  Juan atiende otra urgencia  "));
        var events = events(changed);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(before.get(0));                  // the original stays untouched
        assertThat(events.get(1)).containsEntry("event_type", "REASSIGNMENT").containsEntry("previous_mechanic_user_id", juan)
            .containsEntry("previous_mechanic_name_at_event", "Juan Pérez").containsEntry("new_mechanic_user_id", maria)
            .containsEntry("new_mechanic_name_at_event", "María López").containsEntry("reassignment_reason", "Juan atiende otra urgencia");
        assertThat(events.get(1).get("changed_at")).isEqualTo(row(changed).get("work_started_at"));

        // A preassigned mechanic who left is never a trap: pick another one, with a reason.
        UUID trapped = admitted("P800AAA"); LegacyVisits.preassign(db, trapped, pedro, police);
        db.update("update user_account set is_active=false where id=?", pedro);
        http.perform(get(URL + "/" + trapped).with(police())).andExpect(jsonPath("canStartWork").value(true))
            .andExpect(jsonPath("mechanicEligible").value(false));
        expectUnchanged(trapped, () -> start(trapped, pedro, pedro, null), 400);
        ok(start(trapped, juan, pedro, "Pedro ya no trabaja en el taller"));
        assertThat(row(trapped).get("mechanic_user_id")).isEqualTo(juan);
    }

    @Test void onlyPoliceOfTheVisitWorkshopDrivesTheWorkClock() throws Exception {
        UUID id = admitted("P900AAA");
        for (String role : List.of("ADMIN", "OPERATOR", "SELLER", "CONTROL", "SECRETARY")) {
            var who = auth(admin, workshop, role);
            expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
                .content(startBody(maria, null, null)).with(who)), 403);
            for (String action : List.of("pause-work", "resume-work", "finish-work"))
                expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/" + action).with(who)), 403);
        }
        UUID outsider = user(foreign, "POLICE", true, "Fausto", "Ajeno");
        expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content(startBody(foreignOperator, null, null)).with(auth(outsider, foreign, "POLICE"))), 404);
        ok(start(id, maria, null, null));
        for (String action : List.of("pause-work", "resume-work", "finish-work"))
            expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/" + action).with(auth(outsider, foreign, "POLICE"))), 404);
        TestSecurityContextHolder.clearContext();
        for (String action : List.of("pause-work", "resume-work", "finish-work"))
            expectUnchanged(id, () -> http.perform(post(URL + "/" + id + "/" + action)), 401);
        expectUnchanged(id, () -> http.perform(post(URL + "/" + UUID.randomUUID() + "/pause-work").with(police())), 404);
    }

    // ── Legacy timing: never rewritten, never presented as measured ─────────

    @Test void legacyVisitsKeepTheirRealStartAndAreLabelledHonestly() throws Exception {
        // In progress since before V49 (no intervals): its first pause carries the real start, once.
        UUID running = legacyVisit("P111AAA", juan, gt(DAY, "08:00"), null);
        at(DAY, "09:00");
        var before = read(running);
        assertThat(before.get("work").get("state").asString()).isEqualTo("ACTIVE");
        assertThat(before.get("work").get("basis").asString()).isEqualTo("LEGACY_ELAPSED");
        assertThat(before.get("work").get("activeSeconds").isNull()).isTrue();
        assertDecimal(before.get("work"), "workSeconds", "3600");
        at(DAY, "09:30"); ok(act(running, "pause-work"));
        at(DAY, "10:00"); ok(act(running, "resume-work"));
        at(DAY, "10:30"); var done = ok(act(running, "finish-work"));
        assertWork(done, "FINISHED", "LEGACY_PARTIAL", "7200", "1800", "9000");
        assertThat(intervals(running)).extracting(i -> (String) i.get("origin")).containsExactly("LEGACY_CARRYOVER", "RESUME");
        assertThat(((Timestamp) row(running).get("work_started_at")).toInstant()).isEqualTo(gt(DAY, "08:00"));

        // In progress since before V49 and finished without a pause: one carry-over stretch, start to finish.
        UUID straight = legacyVisit("P222AAA", juan, gt(DAY, "07:00"), null);
        at(DAY, "11:00"); assertWork(ok(act(straight, "finish-work")), "FINISHED", "LEGACY_PARTIAL", "14400", "0", "14400");

        // Finished before V49: elapsed only, no interval is ever fabricated.
        UUID old = legacyVisit("P333AAA", juan, gt(DAY, "06:00"), gt(DAY, "06:30"));
        var oldRow = row(old);
        var oldRead = read(old);
        assertThat(oldRead.get("work").get("basis").asString()).isEqualTo("LEGACY_ELAPSED");
        assertThat(oldRead.get("work").get("activeSeconds").isNull()).isTrue();
        assertThat(oldRead.get("work").get("pausedSeconds").isNull()).isTrue();
        assertDecimal(oldRead.get("work"), "workSeconds", "1800");
        assertThat(intervals(old)).isEmpty();
        assertThat(row(old)).isEqualTo(oldRow);

        UUID measured = admitted("P444AAA");
        at(DAY, "12:00"); ok(start(measured, juan, null, null)); at(DAY, "12:10"); ok(act(measured, "finish-work"));

        var s = report("dateBasis", "FINISHED").get("summary");
        assertThat(s.get("completed").asLong()).isEqualTo(4);
        assertThat(s.get("legacyCompleted").asLong()).isEqualTo(3);    // the mixed basis is disclosed
        assertDecimal(s, "totalSeconds", "24000");                      // 7200 + 14400 + 1800 (historic) + 600
        assertDecimal(s, "averageSeconds", "6000");
        assertThat(s.get("measuredCompleted").asLong()).isEqualTo(1);
        assertDecimal(s, "measuredActiveSeconds", "600");
        assertDecimal(s, "measuredAverageSeconds", "600");
        assertDecimal(s, "pausedSeconds", "1800");                      // only recorded pauses
        assertDecimal(s, "elapsedSeconds", "25800");
        assertThat(jobs(report()).get(old.toString()).get("timingBasis").asString()).isEqualTo("LEGACY_ELAPSED");
    }

    @Test void pausedVisitsAreInProgressNeverCompletedAndHaveNoFinishDate() throws Exception {
        UUID paused = admitted("P555AAA");
        ok(start(paused, maria, null, null)); at(DAY, "10:20"); ok(act(paused, "pause-work"));
        UUID active = admitted("P666AAA");
        ok(start(active, juan, null, null));
        UUID waiting = admitted("P777AAA");
        at(DAY, "10:40");
        var byEntry = report();
        var s = byEntry.get("summary");
        assertThat(s.get("completed").asLong()).isZero();
        assertThat(s.get("inProgress").asLong()).isEqualTo(2);
        assertThat(s.get("paused").asLong()).isEqualTo(1);
        assertThat(s.get("notStarted").asLong()).isEqualTo(1);
        assertThat(s.get("averageSeconds").isNull()).isTrue();
        var jobs = jobs(byEntry);
        assertThat(jobs.get(paused.toString()).get("workState").asString()).isEqualTo("PAUSED");
        assertDecimal(jobs.get(paused.toString()), "activeSeconds", "1200");   // accrued, informational
        assertDecimal(jobs.get(active.toString()), "activeSeconds", "1200");
        assertThat(jobs.get(waiting.toString()).get("workState").asString()).isEqualTo("NOT_STARTED");
        assertThat(report("dateBasis", "FINISHED").get("total").asLong()).isZero();
        assertThat(report("state", "IN_PROGRESS").get("total").asLong()).isEqualTo(2);
    }

    // ── Database guarantees behind the service ─────────────────────────────

    @Test void theDatabaseRejectsOverlapsReopeningAndTimelineRewrites() throws Exception {
        UUID id = admitted("P888AAA");
        ok(start(id, maria, null, null));
        String insert = "insert into vehicle_work_interval(id,vehicle_movement_id,origin,started_at,started_by_user_id) "
            + "values(gen_random_uuid(),?,?,?::timestamptz,?)";
        assertThatThrownBy(() -> db.update(insert, id, "RESUME", "2026-12-01T16:30:00Z", police))   // a second open stretch
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        at(DAY, "10:30"); ok(act(id, "pause-work"));
        assertThatThrownBy(() -> db.update(insert, id, "RESUME", "2026-12-01T16:10:00Z", police))   // overlaps the closed one
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, id, "START", "2026-12-01T16:40:00Z", police))    // a second first stretch
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update vehicle_work_interval set ended_at=ended_at + interval '1 minute' where vehicle_movement_id=?", id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("delete from vehicle_work_interval where vehicle_movement_id=?", id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update vehicle_movement set work_started_at=work_started_at - interval '1 hour' where id=?", id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update vehicle_movement set mechanic_user_id=? where id=?", juan, id))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        at(DAY, "10:40"); ok(act(id, "resume-work"));
        assertThatThrownBy(() -> db.update("update vehicle_movement set work_finished_at=now() where id=?", id))   // open stretch
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID unstarted = admitted("P999AAA");
        assertThatThrownBy(() -> db.update(insert, unstarted, "START", "2026-12-01T16:00:00Z", police))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(intervals(id)).hasSize(2);
    }

    @Test void aFailureAtAnyStepRollsBackTheWholeTransition() throws Exception {
        UUID id = admitted("P121AAA");
        db.execute("CREATE OR REPLACE FUNCTION qa_fail_interval() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'qa forced interval failure'; END $$");
        try {
            db.execute("CREATE TRIGGER qa_fail_interval BEFORE INSERT OR UPDATE ON vehicle_work_interval "
                + "FOR EACH ROW EXECUTE FUNCTION qa_fail_interval()");
            // Mechanic, INITIAL_ASSIGNMENT and start were already written in the transaction: all roll back.
            expectUnchanged(id, () -> start(id, maria, null, null), 500);
            assertThat(row(id).get("mechanic_user_id")).isNull();
            assertThat(events(id)).isEmpty();
        } finally {
            db.execute("DROP TRIGGER IF EXISTS qa_fail_interval ON vehicle_work_interval");
        }
        ok(start(id, maria, null, null));
        try {
            db.execute("CREATE TRIGGER qa_fail_interval BEFORE UPDATE ON vehicle_work_interval "
                + "FOR EACH ROW EXECUTE FUNCTION qa_fail_interval()");
            at(DAY, "10:30");
            expectUnchanged(id, () -> act(id, "pause-work"), 500);
            expectUnchanged(id, () -> act(id, "finish-work"), 500);   // the visit update never survives a failed close
        } finally {
            db.execute("DROP TRIGGER IF EXISTS qa_fail_interval ON vehicle_work_interval");
            db.execute("DROP FUNCTION IF EXISTS qa_fail_interval()");
        }
        assertWork(read(id), "ACTIVE", "MEASURED", "1800", "0", "1800");
        ok(act(id, "pause-work"));
    }

    @Test void samePlateVisitsKeepIndependentMechanicsAndClocks() throws Exception {
        UUID first = admitted("P123ABC");
        ok(start(first, maria, null, null)); at(DAY, "10:30"); ok(act(first, "finish-work")); ok(act(first, "register-exit"));
        UUID second = admitted("P123ABC");
        at(DAY, "11:00"); ok(start(second, juan, null, null)); at(DAY, "11:05"); ok(act(second, "pause-work"));
        assertThat(second).isNotEqualTo(first);
        assertThat(row(first).get("mechanic_user_id")).isEqualTo(maria);
        assertThat(row(second).get("mechanic_user_id")).isEqualTo(juan);
        assertWork(read(first), "FINISHED", "MEASURED", "1800", "0", "1800");
        assertWork(read(second), "PAUSED", "MEASURED", "300", "0", "300");
        var listed = json(http.perform(get(URL).param("plate", "P123ABC").with(police())).andExpect(status().isOk()));
        assertThat(listed.size()).isEqualTo(2);
        for (int i = 0; i < listed.size(); i++)
            assertThat(listed.get(i).get("work").get("state").asString()).isIn("FINISHED", "PAUSED");
    }

    // ── Real overlapping transactions (the second one is seen WAITING on the lock) ──

    @Test void concurrentStartsWithDifferentMechanicsCommitExactlyOne() throws Exception {
        UUID id = admitted("P131AAA");
        var held = hold(() -> work.start(id, new StartWorkRequestDTO(maria, null, null, null)));
        Future<MvcResult> rival = pool.submit(() -> start(id, juan, null, null, auth(police2, workshop, "POLICE")).andReturn());
        awaitLockWait();
        assertThat(rival.isDone()).isFalse();
        held.release().countDown();
        held.result().get(20, TimeUnit.SECONDS);
        assertThat(rival.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        assertThat(row(id).get("mechanic_user_id")).isEqualTo(maria);
        assertThat(events(id)).hasSize(1);
        assertThat(intervals(id)).hasSize(1);
    }

    @Test void concurrentPausesResumesAndFinishesSerialize() throws Exception {
        UUID id = admitted("P141AAA");
        ok(start(id, maria, null, null));
        at(DAY, "10:10");
        race(() -> work.pause(id), id, "pause-work", 400);                 // pause vs pause
        assertThat(intervals(id)).hasSize(1).allSatisfy(i -> assertThat(i.get("ended_at")).isNotNull());
        at(DAY, "10:20");
        race(() -> work.resume(id), id, "resume-work", 400);               // resume vs resume
        assertThat(intervals(id)).hasSize(2);
        assertThat(intervals(id).stream().filter(i -> i.get("ended_at") == null).count()).isEqualTo(1);
        at(DAY, "10:30");
        race(() -> work.pause(id), id, "finish-work", 200);                // pause vs finish: finish from PAUSED
        assertWork(read(id), "FINISHED", "MEASURED", "1200", "600", "1800");
        assertThat(intervals(id)).allSatisfy(i -> assertThat(i.get("ended_at")).isNotNull());

        UUID other = admitted("P151AAA");
        at(DAY, "11:00"); ok(start(other, juan, null, null)); at(DAY, "11:10"); ok(act(other, "pause-work"));
        at(DAY, "11:30");
        race(() -> work.resume(other), other, "finish-work", 200);         // resume vs finish: closes the new stretch
        assertWork(read(other), "FINISHED", "MEASURED", "600", "1200", "1800");
        assertThat(intervals(other)).hasSize(2).allSatisfy(i -> assertThat(i.get("ended_at")).isNotNull());
    }

    @Test void exitNeverSlipsPastAnUnfinishedJobAndStartNeverFollowsAnExit() throws Exception {
        UUID pausing = admitted("P161AAA");
        ok(start(pausing, maria, null, null)); at(DAY, "10:15");
        race(() -> work.pause(pausing), pausing, "register-exit", 400);    // pause vs exit: still unfinished
        assertThat(row(pausing).get("exit")).isNull();

        UUID starting = admitted("P171AAA");
        race(() -> work.start(starting, new StartWorkRequestDTO(juan, null, null, null)), starting, "register-exit", 400);
        assertThat(row(starting).get("exit")).isNull();

        UUID leaving = admitted("P181AAA");
        var held = hold(() -> movements.registerExit(leaving));
        Future<MvcResult> late = pool.submit(() -> start(leaving, juan, null, null).andReturn());
        awaitLockWait();
        held.release().countDown();
        held.result().get(20, TimeUnit.SECONDS);
        assertThat(late.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
        assertThat(row(leaving).get("work_started_at")).isNull();
        assertThat(row(leaving).get("mechanic_user_id")).isNull();
        assertThat(events(leaving)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    record Held<T>(Future<T> result, CountDownLatch release) {}

    /** Runs {@code action} as Police in a transaction that keeps its locks until released. */
    <T> Held<T> hold(Supplier<T> action) throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var template = new TransactionTemplate(transactions);
        Future<T> result = pool.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(token(police, workshop, "POLICE"));
            try {
                return template.execute(status -> {
                    T value = action.get();
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

    /** First holder commits; the HTTP contender must have waited on its lock and then answers {@code expected}. */
    void race(Supplier<?> first, UUID id, String contender, int expected) throws Exception {
        var held = hold(first::get);
        Future<MvcResult> rival = pool.submit(() -> act(id, contender, auth(police2, workshop, "POLICE")).andReturn());
        awaitLockWait();
        assertThat(rival.isDone()).isFalse();
        held.release().countDown();
        held.result().get(20, TimeUnit.SECONDS);
        assertThat(rival.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(expected);
    }

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

    UUID user(UUID shop, String role, boolean active, String first, String last) {
        UUID employee = UUID.randomUUID(), id = UUID.randomUUID();
        db.update("insert into employee(id,workshop_id,first_name,last_name,dpi,birth_date,created_at,updated_at) "
            + "values(?,?,?,?,?,'1990-01-01',now(),now())", employee, shop, first, last, employee.toString().substring(0, 20));
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

    static Instant gt(String day, String time) {
        return LocalDateTime.parse(day + "T" + time).atZone(GT).toInstant();
    }
    void at(String day, String time) { clock.set(gt(day, time)); }

    UUID admitted(String plate) throws Exception {
        var created = json(http.perform(multipart(URL).param("plate", plate).param("phone", "58452136").with(police()))
            .andExpect(status().isCreated()));
        assertThat(created.get("work").get("state").asString()).isEqualTo("NOT_STARTED");
        return UUID.fromString(created.get("id").asString());
    }

    /** A visit as left by the code BEFORE V49: mechanic, start (and maybe finish) but no intervals. */
    UUID legacyVisit(String plate, UUID mechanic, Instant started, Instant finished) {
        UUID id = UUID.randomUUID();
        db.update("insert into vehicle_movement(id,workshop_id,plate,entry,mechanic_user_id,work_started_at,work_finished_at,"
            + "started_by,finished_by,created_at,updated_at) values(?,?,?,?::timestamp,?,?,?,?,?,now(),now())",
            id, workshop, plate, "2026-11-30 07:00:00", mechanic, Timestamp.from(started),
            finished == null ? null : Timestamp.from(finished), police, finished == null ? null : police);
        return id;
    }

    String startBody(UUID mechanic, UUID expected, String reason) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("mechanicId", mechanic == null ? null : mechanic.toString());
        body.put("expectedMechanicId", expected == null ? null : expected.toString());
        if (reason != null) body.put("reason", reason);
        return json.writeValueAsString(body);
    }
    ResultActions start(UUID id, UUID mechanic, UUID expected, String reason) throws Exception {
        return start(id, mechanic, expected, reason, police());
    }
    ResultActions start(UUID id, UUID mechanic, UUID expected, String reason, RequestPostProcessor who) throws Exception {
        return http.perform(post(URL + "/" + id + "/start-work").contentType(MediaType.APPLICATION_JSON)
            .content(startBody(mechanic, expected, reason)).with(who));
    }
    ResultActions act(UUID id, String action) throws Exception { return act(id, action, police()); }
    ResultActions act(UUID id, String action, RequestPostProcessor who) throws Exception {
        return http.perform(post(URL + "/" + id + "/" + action).with(who));
    }

    JsonNode ok(ResultActions result) throws Exception { return json(result.andExpect(status().isOk())); }
    JsonNode json(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
    JsonNode read(UUID id) throws Exception { return ok(http.perform(get(URL + "/" + id).with(police()))); }

    interface Call { ResultActions run() throws Exception; }

    /** A refused request leaves the visit, its intervals and its assignment events exactly as they were. */
    void expectUnchanged(UUID id, Call request, int status) throws Exception {
        var before = state(id);
        assertThat(request.run().andReturn().getResponse().getStatus()).isEqualTo(status);
        assertThat(state(id)).isEqualTo(before);
    }
    List<Object> state(UUID id) { return List.of(row(id), intervals(id), events(id)); }

    void assertWork(JsonNode visit, String state, String basis, String active, String paused, String elapsed) {
        var w = visit.get("work");
        assertThat(w.get("state").asString()).isEqualTo(state);
        assertThat(w.get("basis").asString()).isEqualTo(basis);
        assertDecimal(w, "activeSeconds", active);
        if (paused == null) assertThat(w.get("pausedSeconds").isNull()).isTrue(); else assertDecimal(w, "pausedSeconds", paused);
        assertDecimal(w, "elapsedSeconds", elapsed);
    }
    void assertDecimal(JsonNode node, String field, String expected) {
        assertThat(node.get(field).decimalValue()).as(field).isEqualByComparingTo(new BigDecimal(expected));
    }

    JsonNode report(String... params) throws Exception {
        var request = get(REPORT).with(admin());
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return json(http.perform(request).andExpect(status().isOk()));
    }
    Map<String, JsonNode> jobs(JsonNode report) {
        var out = new HashMap<String, JsonNode>();
        for (int i = 0; i < report.get("jobs").size(); i++) out.put(report.get("jobs").get(i).get("id").asString(), report.get("jobs").get(i));
        return out;
    }

    Map<String, Object> row(UUID id) { return db.queryForMap("select * from vehicle_movement where id=?", id); }
    List<Map<String, Object>> intervals(UUID id) {
        return db.queryForList("select * from vehicle_work_interval where vehicle_movement_id=? order by started_at, id", id);
    }
    List<Map<String, Object>> events(UUID id) {
        return db.queryForList("select * from vehicle_mechanic_assignment_event where vehicle_movement_id=? order by changed_at, id", id);
    }
    List<String> eventTypes(UUID id) { return events(id).stream().map(e -> (String) e.get("event_type")).toList(); }
    static String inst(Object timestamp) { return timestamp == null ? "null" : ((Timestamp) timestamp).toInstant().toString(); }
}
