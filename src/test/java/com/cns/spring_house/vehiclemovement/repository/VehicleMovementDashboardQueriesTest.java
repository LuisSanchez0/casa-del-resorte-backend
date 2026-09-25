package com.cns.spring_house.vehiclemovement.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovementPhoto;
import com.cns.spring_house.vehiclemovement.repository.projection.CurrentVehicleProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.EntriesByDayProjection;
import com.cns.spring_house.vehiclemovement.repository.projection.TopPlateProjection;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Ejecuta las agregaciones del dashboard contra PostgreSQL real sobre una base
 * temporal creada por la propia prueba, igual que
 * {@link VehicleMovementRepositoryTest}. Nunca usa la base de desarrollo.
 *
 * Se prueban aqui y no con mocks porque lo que se esta comprobando ES el SQL:
 * el agrupado por dia, la normalizacion de la placa, el desempate del ranking y
 * el aislamiento por taller ocurren dentro de la sentencia.
 *
 * Todas las pruebas usan DOS talleres: con uno solo, una consulta sin acotar
 * pasaria igualmente.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class VehicleMovementDashboardQueriesTest {

    private static final String TEMPORARY_DATABASE = "vehicle_movement_dashboard_it";

    /** Fecha de referencia fija: las consultas reciben limites explicitos. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Autowired
    private VehicleMovementRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Spring cachea un contexto por clase de prueba y cada uno abre su propio
        // pool. Con el tamano por defecto (10) el conjunto de contextos de la
        // suite agota `max_connections` de PostgreSQL y otras pruebas fallan con
        // "sorry, too many clients already". Esta clase es de un solo hilo, asi
        // que dos conexiones le sobran.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }

    // ── Datos ────────────────────────────────────────────────────

    private Workshop workshop(String name) {
        return entityManager.persistAndFlush(Workshop.builder()
                .name(name)
                .address("DIRECCION")
                .build());
    }

    /** `employee.dpi` es UNIQUE: cada empleado necesita uno distinto. */
    private int dpiSequence;

    private Employee employee(Workshop workshop, String firstName) {
        dpiSequence++;
        return entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName(firstName)
                .lastName("APELLIDO")
                .dpi(String.format("%013d", dpiSequence))
                .phoneNumber("12345678")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    private VehicleMovement movement(
            Workshop workshop, Employee employee, String plate,
            LocalDateTime entry, LocalDateTime exit) {
        return entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshop)
                .employee(employee)
                .plate(plate)
                .entry(entry)
                .exit(exit)
                .build());
    }

    /** Visita ya cerrada: no cuenta como "dentro" pero si como entrada. */
    private VehicleMovement closed(
            Workshop workshop, Employee employee, String plate, LocalDateTime entry) {
        return movement(workshop, employee, plate, entry, entry.plusHours(2));
    }

    // ── Conteos historicos ───────────────────────────────────────

    /**
     * La misma placa entrando ocho veces son OCHO entradas y UNA placa unica. Es
     * la confusion que mas facilmente convierte el dashboard en cifras falsas.
     */
    @Test
    void repeatedVisitsCountAsEntriesWhileThePlateIsUniqueOnlyOnce() {
        Workshop workshopA = workshop("TALLER A");
        Employee ana = employee(workshopA, "ANA");
        for (int visit = 0; visit < 7; visit++) {
            closed(workshopA, ana, "P123ABC", TODAY.minusDays(visit + 1L).atTime(8, 0));
        }
        // Octava visita, todavia abierta.
        movement(workshopA, ana, "P123ABC", TODAY.atTime(8, 0), null);

        assertThat(repository.countByWorkshopId(workshopA.getId())).isEqualTo(8L);
        assertThat(repository.countDistinctPlatesByWorkshop(workshopA.getId())).isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndExitIsNull(workshopA.getId())).isEqualTo(1L);
    }

    /**
     * Placas UNICAS frente a ENTRADAS: un vehiculo que visita el taller dos
     * veces suma dos entradas y una sola placa.
     *
     * Antes esta prueba guardaba la segunda visita en minusculas para comprobar
     * que `COUNT(DISTINCT UPPER(plate))` agrupaba igual. Desde V30 ese valor no
     * se puede guardar: `ck_vehicle_movement_plate_canonical` obliga a que la
     * placa este ya en su forma final, asi que el escenario dejo de ser
     * alcanzable por ningun camino de escritura.
     *
     * El `UPPER(...)` de la consulta se conserva: la restriccion se anadio como
     * NOT VALID por una fila heredada, de modo que normalizar al leer sigue
     * siendo una defensa razonable mientras el pasado no este validado.
     */
    @Test
    void uniquePlatesAreCountedOncePerVehicleAcrossVisits() {
        Workshop workshopA = workshop("TALLER A");
        Employee ana = employee(workshopA, "ANA");
        closed(workshopA, ana, "P123ABC", TODAY.minusDays(3).atTime(8, 0));
        // Segunda VISITA del mismo vehiculo: suma entrada, no suma placa unica.
        closed(workshopA, ana, "P123ABC", TODAY.minusDays(2).atTime(8, 0));
        closed(workshopA, ana, "C456DEF", TODAY.minusDays(1).atTime(8, 0));

        assertThat(repository.countByWorkshopId(workshopA.getId())).isEqualTo(3L);
        assertThat(repository.countDistinctPlatesByWorkshop(workshopA.getId())).isEqualTo(2L);
    }

    @Test
    void everyCountIsIsolatedByWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Employee ana = employee(workshopA, "ANA");
        Employee beto = employee(workshopB, "BETO");

        closed(workshopA, ana, "P123ABC", TODAY.minusDays(1).atTime(8, 0));
        movement(workshopA, ana, "C456DEF", TODAY.atTime(9, 0), null);

        movement(workshopB, beto, "M789GHI", TODAY.atTime(9, 0), null);
        movement(workshopB, beto, "P123ABC", TODAY.atTime(10, 0), null);

        assertThat(repository.countByWorkshopId(workshopA.getId())).isEqualTo(2L);
        assertThat(repository.countByWorkshopId(workshopB.getId())).isEqualTo(2L);
        assertThat(repository.countByWorkshopIdAndExitIsNull(workshopA.getId())).isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndExitIsNull(workshopB.getId())).isEqualTo(2L);
        assertThat(repository.countDistinctPlatesByWorkshop(workshopA.getId())).isEqualTo(2L);
    }

    /**
     * Intervalo semiabierto: 23:59:59 de hoy cuenta y 00:00:00 de manana no. Con
     * un intervalo cerrado, la primera entrada del dia siguiente se sumaria a hoy.
     */
    @Test
    void todayIsAHalfOpenInterval() {
        Workshop workshopA = workshop("TALLER A");
        Employee ana = employee(workshopA, "ANA");
        closed(workshopA, ana, "A111AAA", TODAY.minusDays(1).atTime(23, 59, 59));
        closed(workshopA, ana, "B222BBB", TODAY.atStartOfDay());
        closed(workshopA, ana, "C333CCC", TODAY.atTime(23, 59, 59));
        closed(workshopA, ana, "D444DDD", TODAY.plusDays(1).atStartOfDay());

        long today = repository.countByWorkshopIdAndEntryGreaterThanEqualAndEntryLessThan(
                workshopA.getId(), TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay());

        assertThat(today).isEqualTo(2L);
    }

    // ── Tendencia diaria ─────────────────────────────────────────

    /**
     * El agrupado por dia usa la fecha natural de `entry`, no la hora: dos
     * entradas del mismo dia a horas distintas son un unico punto de valor 2.
     */
    @Test
    void dailyTrendGroupsByCalendarDayAndReturnsOnlyDaysWithActivity() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Employee ana = employee(workshopA, "ANA");
        Employee beto = employee(workshopB, "BETO");

        closed(workshopA, ana, "A111AAA", TODAY.atTime(8, 0));
        closed(workshopA, ana, "B222BBB", TODAY.atTime(20, 30));
        closed(workshopA, ana, "C333CCC", TODAY.minusDays(2).atTime(9, 0));
        // Del taller ajeno: no debe aparecer.
        closed(workshopB, beto, "D444DDD", TODAY.atTime(11, 0));

        List<EntriesByDayProjection> rows = repository.findEntriesByDay(
                workshopA.getId(),
                TODAY.minusDays(29).atStartOfDay(),
                TODAY.plusDays(1).atStartOfDay());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEntryDate()).isEqualTo(TODAY.minusDays(2).toString());
        assertThat(rows.get(0).getEntries()).isEqualTo(1L);
        assertThat(rows.get(1).getEntryDate()).isEqualTo(TODAY.toString());
        assertThat(rows.get(1).getEntries()).isEqualTo(2L);
    }

    // ── Ranking de placas ────────────────────────────────────────

    /**
     * Orden `entries DESC, plate ASC` y limite en SQL. El desempate importa: sin
     * el, dos placas empatadas pueden alternar posicion entre peticiones.
     */
    @Test
    void topPlatesAreOrderedByEntriesThenPlateAndLimitedInSql() {
        Workshop workshopA = workshop("TALLER A");
        Employee ana = employee(workshopA, "ANA");

        // P123ABC: 3 entradas, que deben agruparse en una sola fila del ranking.
        closed(workshopA, ana, "P123ABC", TODAY.minusDays(5).atTime(8, 0));
        closed(workshopA, ana, "P123ABC", TODAY.minusDays(4).atTime(8, 0));
        closed(workshopA, ana, "P123ABC", TODAY.minusDays(3).atTime(8, 0));
        // Empate a 1 entre C456DEF y M789GHI: gana la menor alfabeticamente.
        closed(workshopA, ana, "M789GHI", TODAY.minusDays(2).atTime(8, 0));
        closed(workshopA, ana, "C456DEF", TODAY.minusDays(1).atTime(8, 0));

        List<TopPlateProjection> top = repository.findTopPlates(workshopA.getId(), 10);

        assertThat(top).extracting(TopPlateProjection::getPlate)
                .containsExactly("P123ABC", "C456DEF", "M789GHI");
        assertThat(top).extracting(TopPlateProjection::getEntries)
                .containsExactly(3L, 1L, 1L);
    }

    @Test
    void theTopPlatesLimitIsAppliedByTheDatabase() {
        Workshop workshopA = workshop("TALLER A");
        Employee ana = employee(workshopA, "ANA");
        for (int index = 0; index < 5; index++) {
            closed(workshopA, ana, "P" + (100 + index) + "ABC",
                    TODAY.minusDays(index + 1L).atTime(8, 0));
        }

        assertThat(repository.findTopPlates(workshopA.getId(), 3)).hasSize(3);
    }

    // ── Ambito personal ──────────────────────────────────────────

    /**
     * El ambito personal filtra por actor persistido. Las filas de otro empleado
     * y las que no tienen actor quedan fuera: no se le atribuyen a nadie.
     */
    @Test
    void personalScopeCountsOnlyTheRowsOfThatEmployeeAndNeverTheOnesWithoutActor() {
        Workshop workshopA = workshop("TALLER A");
        Employee ana = employee(workshopA, "ANA");
        Employee luis = employee(workshopA, "LUIS");

        closed(workshopA, ana, "A111AAA", TODAY.minusDays(2).atTime(8, 0));
        movement(workshopA, ana, "B222BBB", TODAY.atTime(8, 0), null);
        closed(workshopA, luis, "C333CCC", TODAY.atTime(9, 0));
        // Movimiento historico sin actor, como los anteriores a V23.
        closed(workshopA, null, "D444DDD", TODAY.atTime(10, 0));

        assertThat(repository.countByWorkshopId(workshopA.getId())).isEqualTo(4L);
        assertThat(repository.countByWorkshopIdAndEmployeeId(
                workshopA.getId(), ana.getId())).isEqualTo(2L);
        assertThat(repository.countByWorkshopIdAndEmployeeId(
                workshopA.getId(), luis.getId())).isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndEmployeeIdAndExitIsNull(
                workshopA.getId(), ana.getId())).isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndEmployeeIsNull(
                workshopA.getId())).isEqualTo(1L);
    }

    /**
     * Un empleado de otro taller no alcanza nada aunque su id sea valido: el
     * taller sigue estando en la sentencia.
     */
    @Test
    void anEmployeeCannotReachAnotherWorkshopThroughThePersonalScope() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Employee ana = employee(workshopA, "ANA");
        closed(workshopA, ana, "A111AAA", TODAY.minusDays(1).atTime(8, 0));

        assertThat(repository.countByWorkshopIdAndEmployeeId(
                workshopB.getId(), ana.getId())).isZero();
        assertThat(repository.findTopPlatesForEmployee(
                workshopB.getId(), ana.getId(), 10)).isEmpty();
        assertThat(repository.findEntriesByDayForEmployee(
                workshopB.getId(), ana.getId(),
                TODAY.minusDays(29).atStartOfDay(),
                TODAY.plusDays(1).atStartOfDay())).isEmpty();
    }

    // ── Vehiculos dentro ─────────────────────────────────────────

    /**
     * "Dentro" es exactamente `exit IS NULL`. La proyeccion trae el conteo de
     * fotografias y conserva las filas sin empleado gracias al LEFT JOIN.
     */
    @Test
    void currentVehiclesUseTheOpenMovementRuleAndKeepRowsWithoutActor() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Employee ana = employee(workshopA, "ANA");
        Employee beto = employee(workshopB, "BETO");

        VehicleMovement open = movement(
                workshopA, ana, "P123ABC", TODAY.atTime(9, 42), null);
        open.addPhoto(VehicleMovementPhoto.builder().photoKey("a.jpg").build());
        open.addPhoto(VehicleMovementPhoto.builder().photoKey("b.jpg").build());
        entityManager.persistAndFlush(open);

        // Sin actor y abierto: debe aparecer, pero sin empleado.
        movement(workshopA, null, "C456DEF", TODAY.atTime(8, 0), null);
        // Cerrado: no esta dentro.
        closed(workshopA, ana, "M789GHI", TODAY.minusDays(1).atTime(8, 0));
        // Abierto pero de otro taller.
        movement(workshopB, beto, "X999XXX", TODAY.atTime(7, 0), null);

        entityManager.clear();

        List<CurrentVehicleProjection> inside =
                repository.findCurrentVehicles(workshopA.getId());

        assertThat(inside).hasSize(2);
        // Orden por entrada descendente.
        assertThat(inside.get(0).getPlate()).isEqualTo("P123ABC");
        assertThat(inside.get(0).getPhotos()).isEqualTo(2);
        assertThat(inside.get(0).getEmployeeId()).isEqualTo(ana.getId());
        assertThat(inside.get(0).getFirstName()).isEqualTo("ANA");

        assertThat(inside.get(1).getPlate()).isEqualTo("C456DEF");
        assertThat(inside.get(1).getPhotos()).isZero();
        assertThat(inside.get(1).getEmployeeId()).isNull();
    }

    // ── Sin datos ────────────────────────────────────────────────

    @Test
    void anEmptyWorkshopReturnsZerosAndEmptyLists() {
        Workshop workshopA = workshop("TALLER A");
        UUID id = workshopA.getId();

        assertThat(repository.countByWorkshopId(id)).isZero();
        assertThat(repository.countByWorkshopIdAndExitIsNull(id)).isZero();
        assertThat(repository.countDistinctPlatesByWorkshop(id)).isZero();
        assertThat(repository.countByWorkshopIdAndEmployeeIsNull(id)).isZero();
        assertThat(repository.findTopPlates(id, 10)).isEmpty();
        assertThat(repository.findCurrentVehicles(id)).isEmpty();
        assertThat(repository.findEntriesByDay(
                id,
                TODAY.minusDays(29).atStartOfDay(),
                TODAY.plusDays(1).atStartOfDay())).isEmpty();
    }

}
