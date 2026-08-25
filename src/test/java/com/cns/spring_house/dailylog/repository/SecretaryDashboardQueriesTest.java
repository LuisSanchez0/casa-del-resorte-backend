package com.cns.spring_house.dailylog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.cashclosure.entity.CashClosure;
import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.cashclosure.repository.projection.CashClosureStampProjection;
import com.cns.spring_house.dailylog.entity.DailyLog;
import com.cns.spring_house.dailylog.repository.projection.DailyLogsByDayProjection;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Las agregaciones del dashboard de secretaria contra PostgreSQL real, sobre
 * una base temporal creada por la propia prueba, igual que
 * {@link com.cns.spring_house.sale.repository.SaleDashboardQueriesTest}.
 *
 * Se prueban aqui y no con mocks porque lo que se comprueba ES el SQL: el
 * aislamiento por taller y por empleado, el agrupado por `performed_date` y los
 * limites del rango ocurren dentro de la sentencia. Un mock del repositorio
 * confirmaria unicamente que el servicio llama al metodo que ya sabemos que
 * llama.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SecretaryDashboardQueriesTest {

    private static final String TEMPORARY_DATABASE = "secretary_dashboard_it";

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);

    @Autowired
    private DailyLogRepository repository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private CashClosureRepository cashClosureRepository;

    @Autowired
    private TestEntityManager entityManager;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry)
            throws IOException, SQLException {
        Properties environment = loadEnvironment();
        String host = value(environment, "DB_HOST", "localhost");
        String port = value(environment, "DB_PORT", "5432");
        String username = value(environment, "DB_USER", "postgres");
        String password = value(environment, "DB_PASS", null);
        String server = "jdbc:postgresql://" + host + ":" + port + "/";

        try (Connection connection = DriverManager.getConnection(
                server + "postgres", username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        registry.add("spring.datasource.url", () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada prueba con base propia se queda con su pool durante toda la
        // ejecucion. Sin este tope la suite agota las conexiones de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }

    private static Properties loadEnvironment() throws IOException {
        Properties environment = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try (InputStream input = Files.newInputStream(envFile)) {
                environment.load(input);
            }
        }
        return environment;
    }

    private static String value(Properties environment, String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return environment.getProperty(key, fallback);
    }

    // ── Datos ────────────────────────────────────────────────────

    private int dpiSequence;

    private Workshop workshop(String name) {
        return entityManager.persistAndFlush(Workshop.builder()
                .name(name)
                .address("DIRECCION")
                .build());
    }

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

    /**
     * Una bitacora sin venta de referencia. El servicio siempre la exige, pero
     * `sale_id` es nullable en el esquema y estas consultas solo cuentan filas:
     * seedear una venta por registro añadiria ruido sin cambiar un solo
     * resultado.
     */
    private DailyLog dailyLog(Workshop workshop, Employee employee, LocalDate performedDate) {
        return entityManager.persistAndFlush(DailyLog.builder()
                .workshop(workshop)
                .employee(employee)
                .performedDate(performedDate)
                .description("REGISTRO")
                .build());
    }

    private Sale completedSale(
            Workshop workshop, Employee employee, InvoiceType invoiceType,
            PaymentStatus paymentStatus, LocalDateTime completedAt) {

        return entityManager.persistAndFlush(Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .status(SaleStatus.COMPLETED)
                .invoiceType(invoiceType)
                .paymentStatus(paymentStatus)
                .paymentMethod(paymentStatus == PaymentStatus.PAID ? PaymentMethod.CASH : null)
                .completedAt(completedAt)
                .paidAt(paymentStatus == PaymentStatus.PAID ? completedAt : null)
                .total(new BigDecimal("100.00"))
                .build());
    }

    private CashClosure cashClosure(Workshop workshop, Employee employee, LocalDate businessDate) {
        BigDecimal zero = BigDecimal.ZERO;
        return entityManager.persistAndFlush(CashClosure.builder()
                .workshop(workshop)
                .closedBy(employee)
                .businessDate(businessDate)
                .openingCash(zero)
                .invoiceCount(0L)
                .invoiceTotal(zero)
                .exchangeInvoiceCount(0L)
                .exchangeInvoiceTotal(zero)
                .totalInvoiced(zero)
                .cashPaymentCount(0L)
                .cashPayments(zero)
                .cardPaymentCount(0L)
                .cardPayments(zero)
                .transferPaymentCount(0L)
                .transferPayments(zero)
                .totalCollected(zero)
                .exchangeCollectedCount(0L)
                .exchangeCollectedAmount(zero)
                .previousExchangeCollectedAmount(zero)
                .pendingExchangeAmount(zero)
                .expectedCash(zero)
                .countedCash(zero)
                .difference(zero)
                .build());
    }

    // ── Bitacoras de hoy ─────────────────────────────────────────

    /** La bitacora del taller cuenta a TODOS sus empleados, no solo a uno. */
    @Test
    void theWorkshopCountIncludesEveryEmployeeOfThatWorkshop() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        Employee eva = employee(taller, "EVA");
        dailyLog(taller, ana, TODAY);
        dailyLog(taller, ana, TODAY);
        dailyLog(taller, eva, TODAY);

        assertThat(repository.countByWorkshopIdAndPerformedDate(taller.getId(), TODAY))
                .isEqualTo(3);
    }

    /** Otro taller no suma, aunque la fecha coincida. */
    @Test
    void theWorkshopCountIsIsolatedByWorkshop() {
        Workshop taller = workshop("TALLER A");
        Workshop otro = workshop("TALLER B");
        dailyLog(taller, employee(taller, "ANA"), TODAY);
        dailyLog(otro, employee(otro, "BEA"), TODAY);
        dailyLog(otro, employee(otro, "BEA"), TODAY);

        assertThat(repository.countByWorkshopIdAndPerformedDate(taller.getId(), TODAY))
                .isEqualTo(1);
        assertThat(repository.countByWorkshopIdAndPerformedDate(otro.getId(), TODAY))
                .isEqualTo(2);
    }

    /** Otro dia tampoco suma: el conteo es de una fecha exacta. */
    @Test
    void theWorkshopCountOnlyIncludesTheGivenDate() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        dailyLog(taller, ana, TODAY);
        dailyLog(taller, ana, TODAY.minusDays(1));

        assertThat(repository.countByWorkshopIdAndPerformedDate(taller.getId(), TODAY))
                .isEqualTo(1);
    }

    // ── Serie personal ───────────────────────────────────────────

    @Test
    void theSeriesGroupsByPerformedDateAndReturnsOnlyDaysWithActivity() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        dailyLog(taller, ana, TODAY.minusDays(2));
        dailyLog(taller, ana, TODAY.minusDays(2));
        dailyLog(taller, ana, TODAY);

        List<DailyLogsByDayProjection> rows = repository.findMyDailyLogsByDay(
                taller.getId(), ana.getId(), TODAY.minusDays(6), TODAY);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(DailyLogsByDayProjection::getLogDate)
                .containsExactly(TODAY.minusDays(2), TODAY);
        assertThat(rows).extracting(DailyLogsByDayProjection::getLogCount)
                .containsExactly(2L, 1L);
    }

    /** Las bitacoras de otra secretaria del MISMO taller no entran en la serie propia. */
    @Test
    void theSeriesExcludesAnotherEmployeeOfTheSameWorkshop() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        Employee eva = employee(taller, "EVA");
        dailyLog(taller, ana, TODAY);
        dailyLog(taller, eva, TODAY);
        dailyLog(taller, eva, TODAY);

        List<DailyLogsByDayProjection> rows = repository.findMyDailyLogsByDay(
                taller.getId(), ana.getId(), TODAY.minusDays(6), TODAY);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getLogCount()).isEqualTo(1);
    }

    /** Un empleado de otro taller no puede leerse desde este, ni conociendo su id. */
    @Test
    void theSeriesExcludesAnotherWorkshop() {
        Workshop taller = workshop("TALLER A");
        Workshop otro = workshop("TALLER B");
        Employee bea = employee(otro, "BEA");
        dailyLog(otro, bea, TODAY);

        assertThat(repository.findMyDailyLogsByDay(
                taller.getId(), bea.getId(), TODAY.minusDays(6), TODAY)).isEmpty();
    }

    /**
     * Los dos extremos del rango son INCLUSIVOS: `performed_date` es DATE, no un
     * instante, asi que el ultimo dia pertenece al periodo. Es el criterio del
     * cierre de caja con `business_date`, no el de las ventanas `[from, to)`
     * sobre `completed_at`.
     */
    @Test
    void bothEndsOfTheRangeAreInclusive() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        dailyLog(taller, ana, TODAY.minusDays(7));
        dailyLog(taller, ana, TODAY.minusDays(6));
        dailyLog(taller, ana, TODAY);
        dailyLog(taller, ana, TODAY.plusDays(1));

        List<DailyLogsByDayProjection> rows = repository.findMyDailyLogsByDay(
                taller.getId(), ana.getId(), TODAY.minusDays(6), TODAY);

        assertThat(rows).extracting(DailyLogsByDayProjection::getLogDate)
                .containsExactly(TODAY.minusDays(6), TODAY);
    }

    @Test
    void anEmployeeWithoutActivityReturnsNoRows() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");

        assertThat(repository.findMyDailyLogsByDay(
                taller.getId(), ana.getId(), TODAY.minusDays(29), TODAY)).isEmpty();
        assertThat(repository.countByWorkshopIdAndPerformedDate(taller.getId(), TODAY))
                .isZero();
    }

    // ── Ventas del panel ─────────────────────────────────────────
    //
    // La serie de ventas reutiliza `findSalesByDay`, la misma consulta que
    // alimentan el dashboard de ventas y el cierre de caja. Lo que se fija aqui
    // es lo que el panel de secretaria DEPENDE de que siga siendo cierto.

    /**
     * Una factura cambiaria emitida y todavia sin cobrar SI cuenta como venta
     * del dia: COMPLETED no significa PAID, el documento ya salio, y es
     * exactamente el caso que la bitacora esta obligada a registrar.
     */
    @Test
    void aCompletedButUnpaidExchangeInvoiceCountsAsASaleOfTheDay() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        completedSale(taller, ana, InvoiceType.EXCHANGE_INVOICE, PaymentStatus.PENDING,
                TODAY.atTime(11, 0));
        completedSale(taller, ana, InvoiceType.INVOICE, PaymentStatus.PAID,
                TODAY.atTime(12, 0));

        List<SalesByDayProjection> rows = saleRepository.findSalesByDay(
                taller.getId(), TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSalesCount()).isEqualTo(2);
    }

    /** Un carrito abierto, una cancelada y una revertida no son ventas del dia. */
    @Test
    void pendingCancelledAndReversedSalesAreNotCounted() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        entityManager.persistAndFlush(Sale.builder()
                .workshop(taller).employee(ana).status(SaleStatus.PENDING)
                .total(new BigDecimal("50.00")).build());
        entityManager.persistAndFlush(Sale.builder()
                .workshop(taller).employee(ana).status(SaleStatus.CANCELLED)
                .completedAt(TODAY.atTime(9, 0)).total(new BigDecimal("50.00")).build());
        entityManager.persistAndFlush(Sale.builder()
                .workshop(taller).employee(ana).status(SaleStatus.REVERSED)
                .invoiceType(InvoiceType.INVOICE)
                .completedAt(TODAY.atTime(10, 0)).total(new BigDecimal("50.00")).build());

        assertThat(saleRepository.findSalesByDay(
                taller.getId(), TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay()))
                .isEmpty();
    }

    // ── Estado del cierre ────────────────────────────────────────

    /** Sin cierre no hay fila, y eso es "la caja sigue abierta", no un error. */
    @Test
    void anOpenDayHasNoClosureStamp() {
        Workshop taller = workshop("TALLER A");

        assertThat(cashClosureRepository
                .findStampByWorkshopIdAndBusinessDate(taller.getId(), TODAY))
                .isEmpty();
    }

    /** `closedAt` es `created_at`: la fila se inserta al cerrar y no se modifica. */
    @Test
    void aClosedDayReportsItsIdentifierAndTheInstantItWasCreated() {
        Workshop taller = workshop("TALLER A");
        Employee ana = employee(taller, "ANA");
        CashClosure closure = cashClosure(taller, ana, TODAY);

        // El cierre se relee de la base antes de comparar. `LocalDateTime.now()`
        // llega con nanosegundos y PostgreSQL guarda TIMESTAMP redondeado a
        // microsegundos: contrastar la proyeccion contra la instancia que sigue
        // en memoria compararia dos precisiones distintas y la prueba fallaria
        // segun el nanosegundo que tocara.
        entityManager.clear();
        CashClosure stored = entityManager.find(CashClosure.class, closure.getId());

        Optional<CashClosureStampProjection> stamp = cashClosureRepository
                .findStampByWorkshopIdAndBusinessDate(taller.getId(), TODAY);

        assertThat(stamp).isPresent();
        assertThat(stamp.get().getId()).isEqualTo(closure.getId());
        assertThat(stamp.get().getClosedAt()).isEqualTo(stored.getCreatedAt());
    }

    /** El cierre de otro taller no responde por este, aunque sea del mismo dia. */
    @Test
    void theClosureStampIsIsolatedByWorkshop() {
        Workshop taller = workshop("TALLER A");
        Workshop otro = workshop("TALLER B");
        cashClosure(otro, employee(otro, "BEA"), TODAY);

        assertThat(cashClosureRepository
                .findStampByWorkshopIdAndBusinessDate(taller.getId(), TODAY))
                .isEmpty();
    }

}
