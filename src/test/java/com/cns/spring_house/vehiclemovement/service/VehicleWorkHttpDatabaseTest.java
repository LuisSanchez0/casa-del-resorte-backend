package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import com.cns.spring_house.testsupport.TestDatabases;

@SpringBootTest
@AutoConfigureMockMvc
class VehicleWorkHttpDatabaseTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    UUID workshop, foreign, police, operator, otherOperator, inactive, admin;
    static final String URL="/v1/vehicle-movements";
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url=TestDatabases.provision("vehicle_work_http_it");
        r.add("spring.datasource.url",()->url);
        r.add("spring.datasource.username",TestDatabases::username);
        r.add("spring.datasource.password",TestDatabases::password);
        r.add("spring.datasource.hikari.maximum-pool-size",()->4);
        r.add("spring.jpa.hibernate.ddl-auto",()->"none");
        r.add("spring.flyway.enabled",()->true);
    }
    @BeforeEach void setup() {
        workshop=shop("QA A"); foreign=shop("QA B");
        police=user(workshop,"POLICE",true); admin=user(workshop,"ADMIN",true);
        operator=user(workshop,"OPERATOR",true); otherOperator=user(foreign,"OPERATOR",true);
        inactive=user(workshop,"OPERATOR",false);
    }
    UUID shop(String name) {
        UUID id=UUID.randomUUID();
        db.update("insert into workshop(id,name,address,is_active,created_at,updated_at) values(?,?,?,true,now(),now())",id,name,"QA");return id;
    }
    UUID user(UUID shop,String role,boolean active) {
        UUID employee=UUID.randomUUID(),id=UUID.randomUUID();
        db.update("insert into employee(id,workshop_id,first_name,last_name,dpi,birth_date,created_at,updated_at) values(?,?,?,? ,?,'1990-01-01',now(),now())",employee,shop,"QA",role,employee.toString().substring(0,20));
        db.update("insert into user_account(id,employee_id,role_id,username,password,is_active,created_at,updated_at) select ?,?,id,?,'unused',?,now(),now() from role where name=?",id,employee,id.toString(),active,role);return id;
    }
    RequestPostProcessor auth(UUID id,UUID shop,String role) {
        return jwt().jwt(j->j.subject(id.toString()).claim("workshop",shop.toString()).claim("role",role))
            .authorities(new SimpleGrantedAuthority("ROLE_"+role));
    }
    RequestPostProcessor police() {return auth(police,workshop,"POLICE");}
    RequestPostProcessor admin() {return auth(admin,workshop,"ADMIN");}
    /** V49: the admission never carries a mechanic; the visit waits, unassigned, for its start. */
    UUID entry() throws Exception {
        String response=http.perform(multipart(URL).param("plate","P123ABC").param("phone","58452136")
            .with(police())).andExpect(status().isCreated())
            .andExpect(jsonPath("mechanic").isEmpty()).andExpect(jsonPath("canStartWork").value(true))
            .andExpect(jsonPath("work.state").value("NOT_STARTED")).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asString());
    }
    /** Starts with the given mechanic, as the start dialog does for a visit that had none. */
    void start(UUID id,UUID mechanic,int status) throws Exception {
        http.perform(post(URL+"/"+id+"/start-work").contentType("application/json")
            .content("{\"mechanicId\":\""+mechanic+"\",\"expectedMechanicId\":null}").with(police()))
            .andExpect(status().is(status));
    }
    void action(UUID id,String action,int status) throws Exception {
        http.perform(post(URL+"/"+id+"/"+action).with(police())).andExpect(status().is(status));
    }
    @Test void admissionNeedsNoMechanicAndRefusesOneFromAStaleClient() throws Exception {
        // Any mechanicId (even an eligible one) is refused explicitly: no visit, no event, no silent drop.
        for(UUID sent:List.of(operator,police,otherOperator,inactive,UUID.randomUUID()))
            http.perform(multipart(URL).param("plate","P123ABC").param("phone","58452136").param("mechanicId",sent.toString()).with(police()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value(org.hamcrest.Matchers.containsString("Iniciar trabajo")));
        assertThat(db.queryForObject("select count(*) from vehicle_movement where workshop_id=?",Long.class,workshop)).isZero();
        UUID id=entry();
        var row=db.queryForMap("select * from vehicle_movement where id=?",id);
        assertThat(row.get("mechanic_user_id")).isNull(); assertThat(row.get("work_started_at")).isNull();
        assertThat(db.queryForObject("select count(*) from vehicle_mechanic_assignment_event where vehicle_movement_id=?",Long.class,id)).isZero();
        assertThat(db.queryForObject("select count(*) from vehicle_work_interval where vehicle_movement_id=?",Long.class,id)).isZero();
        http.perform(get(URL+"/mechanics").with(police())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
    @Test void persistedLifecycleIsSeparateFromExitAndImmutable() throws Exception {
        UUID id=entry(); action(id,"finish-work",400); action(id,"start-work",400);
        Instant before=Instant.now().minusSeconds(1); start(id,operator,200);
        var start=db.queryForObject("select work_started_at from vehicle_movement where id=?",java.sql.Timestamp.class,id);
        assertThat(start.toInstant()).isAfterOrEqualTo(before);
        start(id,operator,400); action(id,"register-exit",400);
        action(id,"finish-work",200); action(id,"finish-work",400); start(id,operator,400);
        var row=db.queryForMap("select * from vehicle_movement where id=?",id);
        assertThat(row.get("work_started_at")).isEqualTo(start);
        assertThat(row.get("exit")).isNull();
        assertThat(row.get("started_by")).isEqualTo(police); assertThat(row.get("finished_by")).isEqualTo(police);
        assertThat(((java.sql.Timestamp)row.get("work_finished_at")).toInstant()).isAfterOrEqualTo(start.toInstant());
        http.perform(get(URL+"/"+id).with(police())).andExpect(jsonPath("workFinishedAt").isNotEmpty()).andExpect(jsonPath("durationSeconds").isNumber());
        action(id,"register-exit",200); start(id,operator,400);
        UUID second=entry(); assertThat(second).isNotEqualTo(id);
        start(second,user(workshop,"OPERATOR",true),200);
        assertThat(db.queryForObject("select mechanic_user_id from vehicle_movement where id=?",UUID.class,id)).isEqualTo(operator);
    }
    @Test void rolesWorkshopAndClientTimestampsAreEnforced() throws Exception {
        UUID id=entry();
        for(String role:List.of("ADMIN","OPERATOR","SELLER","CONTROL","SECRETARY")) {
            http.perform(post(URL+"/"+id+"/start-work").with(auth(admin,workshop,role))).andExpect(status().isForbidden());
            http.perform(post(URL+"/"+id+"/finish-work").with(auth(admin,workshop,role))).andExpect(status().isForbidden());
            http.perform(post(URL+"/"+id+"/register-exit").with(auth(admin,workshop,role))).andExpect(status().isForbidden());
        }
        UUID outsider=user(foreign,"POLICE",true);
        for(String action:List.of("start-work","finish-work","register-exit"))
            http.perform(post(URL+"/"+id+"/"+action).with(auth(outsider,foreign,"POLICE"))).andExpect(status().isNotFound());
        http.perform(post(URL+"/"+id+"/start-work").contentType("application/json").content("{\"workStartedAt\":\"2020-01-01T00:00:00Z\"}").with(police())).andExpect(status().isBadRequest());
        http.perform(get("/v1/vehicle-work-report").with(police())).andExpect(status().isForbidden());
        http.perform(get("/v1/vehicle-work-report/"+id).with(auth(admin,foreign,"ADMIN"))).andExpect(status().isNotFound());
        http.perform(get("/v1/vehicle-work-report").param("size","101").with(admin())).andExpect(status().isBadRequest());
        http.perform(get("/v1/vehicle-work-report").param("from","2026-02-02").param("to","2026-02-01").with(admin())).andExpect(status().isBadRequest());
    }
    @Test void concurrentStartsAndFinishesHaveOneWinner() throws Exception {
        UUID id=entry();
        for(String action:List.of("start-work","finish-work")) {
            var gate=new CountDownLatch(1);
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Integer>> calls=new ArrayList<>();
                for(int i=0;i<2;i++) calls.add(executor.submit(()->{
                    gate.await();
                    var request=post(URL+"/"+id+"/"+action).with(police());
                    if(action.equals("start-work")) request.contentType("application/json")
                        .content("{\"mechanicId\":\""+operator+"\",\"expectedMechanicId\":null}");
                    return http.perform(request).andReturn().getResponse().getStatus();
                }));
                gate.countDown();var results=new ArrayList<Integer>();for(var call:calls) results.add(call.get(20,TimeUnit.SECONDS));
                assertThat(results).containsExactlyInAnyOrder(200,400);
            }
        }
    }
    @Test void reportCountsVisitsAndExcludesUnfinishedEvenAfterDeactivation() throws Exception {
        UUID first=entry(); seededFinish(first, "2026-11-16 00:00:00",60);
        UUID second=entry(); seededFinish(second,"2026-11-16 23:59:59",180);
        UUID pending=entry();
        db.update("update vehicle_movement set entry='2026-11-16 12:00:00' where id=?",pending);
        db.update("update user_account set is_active=false where id=?",operator);
        http.perform(get("/v1/vehicle-work-report").param("from","2026-11-16").param("to","2026-11-16").param("size","1").with(admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("summary.completed").value(2))
            .andExpect(jsonPath("summary.notStarted").value(1)).andExpect(jsonPath("summary.totalSeconds").value(240))
            .andExpect(jsonPath("summary.averageSeconds").value(120)).andExpect(jsonPath("total").value(3))
            .andExpect(jsonPath("jobs.length()").value(1)).andExpect(jsonPath("mechanics[0].mechanicId").value(operator.toString()));
        http.perform(get("/v1/vehicle-work-report").param("state","NOT_STARTED").with(admin()))
            .andExpect(jsonPath("summary.completed").value(0)).andExpect(jsonPath("summary.averageSeconds").isEmpty());
        start(pending,operator,400);
        assertThat(db.queryForObject("select mechanic_user_id from vehicle_movement where id=?",UUID.class,pending)).isNull();
    }
    @Test void legacyNullTimingAndZeroDurationAreRealValues() throws Exception {
        UUID legacy=UUID.randomUUID();
        db.update("insert into vehicle_movement(id,workshop_id,plate,entry,created_at,updated_at) values(?,?,'P999ABC',now(),now(),now())",legacy,workshop);
        action(legacy,"start-work",400);
        http.perform(get(URL+"/"+legacy).with(police())).andExpect(status().isOk()).andExpect(jsonPath("mechanic").isEmpty());
        UUID zero=entry();seededFinish(zero,"2026-11-17 12:00:00",0);
        http.perform(get("/v1/vehicle-work-report").with(admin())).andExpect(jsonPath("summary.completed").value(1)).andExpect(jsonPath("summary.averageSeconds").value(0));
        assertThatThrownBy(()->db.update("update vehicle_movement set work_finished_at=now() where id=?",legacy)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    /** A visit finished BEFORE V49 (no intervals): mechanic, start, finish and exit as the old code left them. */
    void seededFinish(UUID id,String entry,int seconds) {
        db.update("update vehicle_movement set entry=?::timestamp, mechanic_user_id=?, work_started_at='2026-11-17T10:00:00Z', work_finished_at='2026-11-17T10:00:00Z'::timestamptz + (? * interval '1 second'), exit='2026-11-17 12:00:00' where id=?",entry,operator,seconds,id);
    }
}
