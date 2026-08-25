package com.cns.spring_house.cashclosure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.dto.CashClosureSummaryResponseDTO;
import com.cns.spring_house.cashclosure.dto.CreateCashClosureRequestDTO;
import com.cns.spring_house.cashclosure.repository.CashClosureRepository;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.service.SaleService;
import com.cns.spring_house.shared.config.time.TimeConfig;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.repository.WorkResultRepository;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El cierre de caja con transacciones reales contra PostgreSQL.
 *
 * Aqui se comprueba lo que ningun mock puede demostrar: que el snapshot deja de
 * depender de `sale` en cuanto se persiste, que la restriccion UNIQUE impide un
 * segundo cierre aunque la comprobacion previa se saltara, y que una venta no
 * puede colarse despues de un cierre ya confirmado ni siquiera cuando las dos
 * operaciones ocurren a la vez.
 *
 * El reloj es un {@link MutableClock} para poder situar la fecha operativa en
 * dias concretos —y en el dia siguiente— sin esperar a que pase el tiempo. Es
 * el MISMO bean que usan el cierre y el bloqueo de ventas, que es justamente lo
 * que hace que ambos hablen siempre del mismo dia.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CashClosureServiceDatabaseTest {

    private static final String TEMPORARY_DATABASE = "cash_closure_service_it";

    private static final LocalDate DAY_ONE = LocalDate.of(2026, 8, 1);
    private static final LocalDate DAY_TWO = LocalDate.of(2026, 8, 2);
    private static final LocalDate DAY_THREE = LocalDate.of(2026, 8, 3);
    private static final LocalDate DAY_TEN = LocalDate.of(2026, 8, 10);

    /**
     * Reloj que la prueba puede mover. Sustituye al del sistema para TODO el
     * contexto, asi que cierre y bloqueo de ventas siguen compartiendo reloj.
     */
    static final class MutableClock extends Clock {

        private volatile Instant instant =
                LocalDate.of(2026, 8, 20).atTime(12, 0)
                        .atZone(TimeConfig.BUSINESS_ZONE).toInstant();

        void moveTo(LocalDate date) {
            moveTo(date.atTime(18, 30));
        }

        /** Para las pruebas de frontera, donde la hora exacta es el objeto de estudio. */
        void moveTo(LocalDateTime moment) {
            this.instant = moment.atZone(TimeConfig.BUSINESS_ZONE).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return TimeConfig.BUSINESS_ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class FixedTimeConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private CashClosureService service;

    @Autowired
    private CashClosureRepository repository;

    @Autowired
    private SaleService saleService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private com.cns.spring_house.sale.repository.SaleDetailRepository saleDetailRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobLogRepository jobLogRepository;

    @Autowired
    private WorkResultRepository workResultRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        // Cada clase de prueba con base propia mantiene su contexto —y su pool—
        // vivo durante toda la ejecucion de la suite, y PostgreSQL corta a las
        // 100 conexiones. Cinco bastan aqui: el hilo de la prueba mas los dos
        // que se enfrentan en las pruebas de concurrencia, con margen.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
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

    @BeforeEach
    void resetClock() {
        clock.moveTo(LocalDate.of(2026, 8, 20));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Datos ────────────────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private final Map<UUID, UserAccount> accounts = new HashMap<>();

    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        authenticateAgainst(workshop);
        return workshop;
    }

    private void authenticateAgainst(Workshop workshop) {
        UserAccount account = accounts.computeIfAbsent(
                workshop.getId(), id -> persistAuthenticatedUser(workshop));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokenFor(account, workshop)));
    }

    private Jwt tokenFor(UserAccount account, Workshop workshop) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
    }

    private UserAccount persistAuthenticatedUser(Workshop workshop) {
        Role seller = roleRepository.findAll().stream()
                .filter(role -> "SELLER".equals(role.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El rol SELLER no esta sembrado en la base de prueba"));
        return userAccountRepository.save(UserAccount.builder()
                .employee(employee(workshop))
                .role(seller)
                .username("actor_" + unique())
                .password("no-usada-en-pruebas")
                .isActive(true)
                .build());
    }

    private Employee employee(Workshop workshop) {
        return employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("ANA")
                .lastName("LOPEZ")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    private Customer customer() {
        return customerRepository.save(Customer.builder()
                .name("CLIENTE " + unique())
                .nit(UUID.randomUUID().toString().substring(0, 10))
                .build());
    }

    private Sale invoice(
            Workshop workshop, LocalDate day, PaymentMethod method, String total) {
        return saleRepository.save(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .paymentMethod(method)
                .completedAt(day.atTime(10, 0))
                .paidAt(day.atTime(10, 0))
                .total(new BigDecimal(total))
                .build());
    }

    private Sale pendingExchangeInvoice(Workshop workshop, LocalDate day, String total) {
        return saleRepository.save(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(day.atTime(11, 0))
                .total(new BigDecimal(total))
                .build());
    }

    private void collect(Sale sale, LocalDate day, PaymentMethod method) {
        sale.setPaymentStatus(PaymentStatus.PAID);
        sale.setPaymentMethod(method);
        sale.setPaidAt(day.atTime(15, 0));
        saleRepository.save(sale);
    }

    private CreateCashClosureRequestDTO arqueo(String opening, String counted) {
        return new CreateCashClosureRequestDTO(
                new BigDecimal(opening), new BigDecimal(counted), null);
    }

    private CashClosureResponseDTO closeOn(LocalDate day, String opening, String counted) {
        clock.moveTo(day);
        return service.close(arqueo(opening, counted));
    }

    // ── Snapshot inmutable ───────────────────────────────────────

    /**
     * Lo que define al snapshot: despues de cerrar, los datos de origen pueden
     * cambiar —se cobra una cambiaria, se emite otra factura— y el documento
     * historico sigue diciendo exactamente lo que se confirmo.
     */
    @Test
    void aConfirmedClosureKeepsItsFiguresWhenTheSourceSalesChangeAfterwards() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        Sale pending = pendingExchangeInvoice(workshop, DAY_ONE, "5000.00");

        CashClosureResponseDTO closed = service.close(arqueo("500.00", "1500.00"));
        UUID closureId = closed.id();

        assertThat(closed.invoicing().totalInvoiced()).isEqualByComparingTo("6000.00");
        assertThat(closed.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(closed.receivables().pendingAmount()).isEqualByComparingTo("5000.00");
        assertThat(closed.cashReconciliation().difference()).isEqualByComparingTo("0.00");

        // El mundo sigue: se cobra la cambiaria y se factura mas ese mismo dia.
        collect(pending, DAY_ONE, PaymentMethod.TRANSFER);
        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "9999.00");

        CashClosureResponseDTO reread = service.get(closureId);

        assertThat(reread.invoicing().totalInvoiced()).isEqualByComparingTo("6000.00");
        assertThat(reread.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(reread.receivables().pendingAmount()).isEqualByComparingTo("5000.00");
        assertThat(reread.cashReconciliation().expectedCash()).isEqualByComparingTo("1500.00");
        assertThat(reread.closedAt()).isNotNull();
        assertThat(reread.closedBy()).isEqualTo("ANA LOPEZ");
    }

    @Test
    void theSnapshotIsAlsoWhatByDateAndTheRangeReturn() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);
        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        UUID closureId = service.close(arqueo("0.00", "1000.00")).id();

        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "5000.00");

        assertThat(service.getByDate(DAY_ONE).id()).isEqualTo(closureId);
        assertThat(service.getByDate(DAY_ONE).invoicing().totalInvoiced())
                .isEqualByComparingTo("1000.00");
        assertThat(service.getRange(DAY_ONE, DAY_ONE))
                .singleElement()
                .satisfies(closure -> assertThat(closure.collections().totalCollected())
                        .isEqualByComparingTo("1000.00"));
    }

    // ── Un cierre por taller y dia ───────────────────────────────

    @Test
    void theSameWorkshopCannotCloseTheSameDayTwice() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);
        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");

        service.close(arqueo("0.00", "1000.00"));

        assertThatThrownBy(() -> service.close(arqueo("0.00", "1000.00")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cerrada");

        assertThat(repository.findByWorkshopIdAndBusinessDateBetweenOrderByBusinessDateDescIdDesc(
                workshop.getId(), DAY_ONE, DAY_ONE)).hasSize(1);
    }

    /**
     * La comprobacion del servicio da un mensaje entendible, pero la garantia
     * real es del esquema: insertando por debajo del servicio, la restriccion
     * UNIQUE sigue impidiendo el segundo snapshot.
     */
    @Test
    void theUniqueConstraintStopsASecondSnapshotEvenBypassingTheService() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);
        service.close(arqueo("0.00", "0.00"));

        var duplicate = com.cns.spring_house.cashclosure.entity.CashClosure.builder()
                .workshop(workshop)
                .closedBy(employee(workshop))
                .businessDate(DAY_ONE)
                .openingCash(BigDecimal.ZERO)
                .invoiceCount(0).invoiceTotal(BigDecimal.ZERO)
                .exchangeInvoiceCount(0).exchangeInvoiceTotal(BigDecimal.ZERO)
                .totalInvoiced(BigDecimal.ZERO)
                .cashPaymentCount(0).cashPayments(BigDecimal.ZERO)
                .cardPaymentCount(0).cardPayments(BigDecimal.ZERO)
                .transferPaymentCount(0).transferPayments(BigDecimal.ZERO)
                .totalCollected(BigDecimal.ZERO)
                .exchangeCollectedCount(0).exchangeCollectedAmount(BigDecimal.ZERO)
                .previousExchangeCollectedAmount(BigDecimal.ZERO)
                .pendingExchangeAmount(BigDecimal.ZERO)
                .expectedCash(BigDecimal.ZERO).countedCash(BigDecimal.ZERO)
                .difference(BigDecimal.ZERO)
                .build();

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoWorkshopsCanCloseTheSameDateIndependently() {
        Workshop workshopA = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);
        invoice(workshopA, DAY_ONE, PaymentMethod.CASH, "1000.00");
        CashClosureResponseDTO closureA = service.close(arqueo("0.00", "1000.00"));

        Workshop workshopB = authenticatedWorkshop();
        invoice(workshopB, DAY_ONE, PaymentMethod.CASH, "2000.00");
        CashClosureResponseDTO closureB = service.close(arqueo("0.00", "2000.00"));

        assertThat(closureA.id()).isNotEqualTo(closureB.id());
        assertThat(closureA.collections().cashAmount()).isEqualByComparingTo("1000.00");
        assertThat(closureB.collections().cashAmount()).isEqualByComparingTo("2000.00");

        // Y ninguno ve nada del otro.
        authenticateAgainst(workshopA);
        assertThat(service.getByDate(DAY_ONE).id()).isEqualTo(closureA.id());
        assertThatThrownBy(() -> service.get(closureB.id()))
                .isInstanceOf(com.cns.spring_house.shared.exception.EntityNotFoundException.class);
    }

    // ── Rango ────────────────────────────────────────────────────

    @Test
    void theRangeIncludesExactlyTheClosuresBetweenBothEndsInclusive() {
        Workshop workshop = authenticatedWorkshop();
        closeOn(DAY_ONE, "0.00", "0.00");
        closeOn(DAY_TWO, "0.00", "0.00");
        closeOn(DAY_THREE, "0.00", "0.00");
        closeOn(DAY_TEN, "0.00", "0.00");

        List<CashClosureResponseDTO> range = service.getRange(DAY_ONE, DAY_THREE);

        assertThat(range).hasSize(3);
        assertThat(range).extracting(CashClosureResponseDTO::businessDate)
                .containsExactly(DAY_THREE, DAY_TWO, DAY_ONE);
        assertThat(service.getSummary(DAY_ONE, DAY_THREE).closureCount()).isEqualTo(3);
        assertThat(workshop.getId()).isNotNull();
    }

    /**
     * Un rango de 31 dias con 25 cierres reporta 25, no 31: los seis dias sin
     * cerrar no se inventan como jornadas en cero, porque no consta que nadie
     * cuadrara la caja.
     */
    @Test
    void daysWithoutAClosureAreNotInventedAsZeroDays() {
        authenticatedWorkshop();
        closeOn(DAY_ONE, "0.00", "0.00");
        closeOn(DAY_THREE, "0.00", "0.00");

        CashClosureSummaryResponseDTO summary =
                service.getSummary(DAY_ONE, LocalDate.of(2026, 8, 31));

        assertThat(summary.closureCount()).isEqualTo(2);
        assertThat(summary.days()).hasSize(2);
        // El detalle del periodo se lee en orden cronologico, al reves que el
        // listado de cierres.
        assertThat(summary.days()).extracting(day -> day.businessDate())
                .containsExactly(DAY_ONE, DAY_THREE);
    }

    /**
     * El caso que hace falso cualquier resumen mal hecho: tres dias con Q20,000,
     * Q18,000 y Q25,000 pendientes NO son Q63,000 de cartera. Es Q25,000, el
     * saldo del ultimo cierre.
     */
    @Test
    void theEndingPortfolioIsTheLastClosureBalanceAndNotTheSumOfTheDailyOnes() {
        Workshop workshop = authenticatedWorkshop();

        Sale big = pendingExchangeInvoice(workshop, DAY_ONE, "18000.00");
        Sale small = pendingExchangeInvoice(workshop, DAY_ONE, "2000.00");
        closeOn(DAY_ONE, "100.00", "100.00");

        collect(small, DAY_TWO, PaymentMethod.CARD);
        closeOn(DAY_TWO, "100.00", "80.00");

        pendingExchangeInvoice(workshop, DAY_THREE, "7000.00");
        closeOn(DAY_THREE, "100.00", "105.00");

        assertThat(service.getByDate(DAY_ONE).receivables().pendingAmount())
                .isEqualByComparingTo("20000.00");
        assertThat(service.getByDate(DAY_TWO).receivables().pendingAmount())
                .isEqualByComparingTo("18000.00");
        assertThat(service.getByDate(DAY_THREE).receivables().pendingAmount())
                .isEqualByComparingTo("25000.00");

        CashClosureSummaryResponseDTO summary = service.getSummary(DAY_ONE, DAY_THREE);

        assertThat(summary.receivables().endingPendingAmount())
                .isEqualByComparingTo("25000.00");
        assertThat(big.getId()).isNotNull();
    }

    /** Cuadres del rango: uno cuadrado, uno con faltante y uno con sobrante. */
    @Test
    void theRangeCountsBalancesAndAddsUpTheNetDifference() {
        authenticatedWorkshop();
        closeOn(DAY_ONE, "100.00", "100.00");
        closeOn(DAY_TWO, "100.00", "80.00");
        closeOn(DAY_THREE, "100.00", "105.00");

        CashClosureSummaryResponseDTO summary = service.getSummary(DAY_ONE, DAY_THREE);

        assertThat(summary.balance().balancedClosures()).isEqualTo(1);
        assertThat(summary.balance().shortClosures()).isEqualTo(1);
        assertThat(summary.balance().overClosures()).isEqualTo(1);
        assertThat(summary.balance().netCashDifference()).isEqualByComparingTo("-15.00");
    }

    /** El resumen mensual es este mismo rango con los extremos del mes. */
    @Test
    void aMonthlySummaryIsJustARangeOverTheMonthEnds() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);
        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        service.close(arqueo("0.00", "1000.00"));

        clock.moveTo(DAY_TEN);
        invoice(workshop, DAY_TEN, PaymentMethod.CARD, "2000.00");
        service.close(arqueo("0.00", "0.00"));

        CashClosureSummaryResponseDTO month = service.getSummary(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(month.closureCount()).isEqualTo(2);
        assertThat(month.invoicing().totalInvoiced()).isEqualByComparingTo("3000.00");
        assertThat(month.collections().cashAmount()).isEqualByComparingTo("1000.00");
        assertThat(month.collections().cardAmount()).isEqualByComparingTo("2000.00");
        assertThat(month.collections().totalCollected()).isEqualByComparingTo("3000.00");
    }

    @Test
    void theRangeSummaryOfOneWorkshopNeverIncludesAnother() {
        Workshop workshopA = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);
        invoice(workshopA, DAY_ONE, PaymentMethod.CASH, "1000.00");
        service.close(arqueo("0.00", "1000.00"));

        Workshop workshopB = authenticatedWorkshop();
        invoice(workshopB, DAY_ONE, PaymentMethod.CASH, "8888.00");
        service.close(arqueo("0.00", "8888.00"));

        authenticateAgainst(workshopA);
        CashClosureSummaryResponseDTO summary = service.getSummary(DAY_ONE, DAY_THREE);

        assertThat(summary.closureCount()).isEqualTo(1);
        assertThat(summary.collections().cashAmount()).isEqualByComparingTo("1000.00");
    }

    // ── Bloqueo de ventas ────────────────────────────────────────

    @Test
    void aNewSaleIsAllowedWhileTheRegisterIsStillOpen() {
        authenticatedWorkshop();
        Customer client = customer();

        var sale = saleService.create(new CreateSaleRequestDTO(client.getId(), null));

        assertThat(sale.id()).isNotNull();
        assertThat(saleRepository.findById(sale.id())).isPresent();
    }

    @Test
    void aNewSaleIsRejectedAndNotPersistedOnceTheDayIsClosed() {
        Workshop workshop = authenticatedWorkshop();
        Customer client = customer();
        long before = saleRepository.count();

        service.close(arqueo("0.00", "0.00"));

        assertThatThrownBy(() -> saleService.create(
                new CreateSaleRequestDTO(client.getId(), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La caja del dia ya fue cerrada. "
                        + "No se pueden registrar nuevas ventas ni cobros");

        assertThat(saleRepository.count()).isEqualTo(before);
        assertThat(workshop.getId()).isNotNull();
    }

    /** El cierre de un taller no puede paralizar a otro. */
    @Test
    void theClosureOfOneWorkshopDoesNotBlockSalesInAnother() {
        authenticatedWorkshop();
        service.close(arqueo("0.00", "0.00"));

        Workshop open = authenticatedWorkshop();
        Customer client = customer();

        var sale = saleService.create(new CreateSaleRequestDTO(client.getId(), null));

        assertThat(sale.id()).isNotNull();
        assertThat(open.getId()).isNotNull();
    }

    /** Cerrar hoy no cierra el negocio para siempre: manana se vuelve a vender. */
    @Test
    void theNextBusinessDayIsOpenAgain() {
        authenticatedWorkshop();
        Customer client = customer();

        clock.moveTo(DAY_ONE);
        service.close(arqueo("0.00", "0.00"));

        assertThatThrownBy(() -> saleService.create(
                new CreateSaleRequestDTO(client.getId(), null)))
                .isInstanceOf(ValidationException.class);

        clock.moveTo(DAY_TWO);
        var sale = saleService.create(new CreateSaleRequestDTO(client.getId(), null));

        assertThat(sale.id()).isNotNull();
    }

    /** Ni la previsualizacion ni su PDF cierran ni bloquean nada. */
    @Test
    void previewingAndExportingTheDraftNeitherClosesTheRegisterNorBlocksSales() {
        Workshop workshop = authenticatedWorkshop();
        Customer client = customer();

        service.getPreview();
        service.preview(arqueo("500.00", "500.00"));
        byte[] draft = service.previewDocument(arqueo("500.00", "500.00")).content();

        assertThat(draft).isNotEmpty();
        assertThat(repository.findByWorkshopIdAndBusinessDate(
                workshop.getId(), LocalDate.now(clock))).isEmpty();
        assertThat(saleService.create(new CreateSaleRequestDTO(client.getId(), null)).id())
                .isNotNull();
    }


    // ── Fixtures de venta facturable ─────────────────────────────

    /**
     * Una venta abierta con una linea de servicio y su trabajo reservado.
     *
     * Se usa el camino JOB y no el de inventario porque arrastra un
     * {@link WorkResult}: al emitir, {@code completeSale} lo marca como SOLD
     * ANTES de guardar la venta, asi que es el efecto colateral mas temprano que
     * existe y sirve de prueba de que el guard corta antes de tocar nada.
     */
    private record PendingSale(UUID id, UUID workResultId, BigDecimal total) {
    }

    private PendingSale pendingSaleWithWork(Workshop workshop) {
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = saleRepository.save(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .build());
        SaleResponseDTO withDetail = saleService.addDetail(
                sale.getId(),
                new AddSaleDetailRequestDTO(
                        jobProduct.getId(), null, workResult.getId(), 1, null, null));
        return new PendingSale(sale.getId(), workResult.getId(), withDetail.total());
    }

    /**
     * Venta abierta con una linea que NO nace de un trabajo pendiente.
     *
     * Es la variante que deja al descubierto la carrera de doble emision: en la
     * variante con WorkResult, `markAsSold` toma su propio cerrojo sobre el
     * trabajo y rechaza la segunda emision de rebote, tapando el problema real.
     * Una venta de mostrador no tiene esa red.
     */
    private UUID pendingSaleWithoutWork(Workshop workshop) {
        Product jobProduct = product(FamilyCategory.JOB);
        Sale sale = saleRepository.save(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .total(new BigDecimal("500.00"))
                .build());
        saleDetailRepository.save(SaleDetail.builder()
                .sale(sale)
                .product(jobProduct)
                .quantity(1)
                .price(new BigDecimal("500.00"))
                .subTotal(new BigDecimal("500.00"))
                .build());
        return sale.getId();
    }

    private Product product(FamilyCategory category) {
        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .build());
        return productRepository.save(Product.builder()
                .family(family)
                .name("PRODUCTO " + code)
                .attributes(new HashMap<>())
                .build());
    }

    private WorkResult pendingJobWorkResult(Workshop workshop, Product jobProduct) {
        Job job = jobRepository.save(Job.builder()
                .product(jobProduct)
                .workshop(workshop)
                .price(new BigDecimal("150.00"))
                .build());
        JobLog jobLog = jobLogRepository.save(JobLog.builder()
                .employee(employee(workshop))
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        return workResultRepository.save(WorkResult.builder()
                .workshop(workshop)
                .jobLog(jobLog)
                .build());
    }

    private WorkResultStatus statusOf(UUID workResultId) {
        return workResultRepository.findById(workResultId).orElseThrow().getStatus();
    }

    private Sale reload(UUID saleId) {
        return saleRepository.findById(saleId).orElseThrow();
    }

    private CompleteSaleRequestDTO invoiceCash() {
        return new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH);
    }

    // ── Emitir el documento despues del cierre ───────────────────

    /**
     * El agujero que quedaba abierto: una venta creada a las 17:50, un cierre a
     * las 18:00 y `complete` a las 18:05 anadia facturacion Y cobro a un dia
     * cuyo snapshot ya estaba firmado, y que el UNIQUE impide volver a cerrar.
     *
     * No basta con que lance: se comprueba que no dejo NINGUN rastro, incluido
     * el WorkResult, que `completeSale` marca como vendido antes de guardar.
     */
    @Test
    void aPendingSaleCannotBeInvoicedOnceTheDayIsClosed() {
        Workshop workshop = authenticatedWorkshop();
        PendingSale pending = pendingSaleWithWork(workshop);

        service.close(arqueo("0.00", "0.00"));

        assertThatThrownBy(() -> saleService.completeSale(pending.id(), invoiceCash()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cerrada");

        Sale stored = reload(pending.id());
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(stored.getCompletedAt()).isNull();
        assertThat(stored.getInvoiceType()).isNull();
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getPaidAt()).isNull();
        assertThat(statusOf(pending.workResultId()))
                .as("el guard corta antes de marcar el trabajo como vendido")
                .isEqualTo(WorkResultStatus.PENDING);
        assertThat(workshop.getId()).isNotNull();
    }

    /** Sin cierre, emitir sigue funcionando exactamente igual que antes. */
    @Test
    void invoicingBeforeTheClosureStillBehavesExactlyAsBefore() {
        Workshop workshop = authenticatedWorkshop();
        PendingSale pending = pendingSaleWithWork(workshop);

        saleService.completeSale(pending.id(), invoiceCash());

        Sale stored = reload(pending.id());
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(stored.getInvoiceType()).isEqualTo(InvoiceType.INVOICE);
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(stored.getPaidAt()).isNotNull();
        assertThat(statusOf(pending.workResultId())).isEqualTo(WorkResultStatus.SOLD);
        assertThat(workshop.getId()).isNotNull();
    }

    // ── Cobrar despues del cierre ────────────────────────────────

    /**
     * Cobrar una cambiaria emitida antes del cierre tambien es dinero que entra
     * ese dia. Con la caja cerrada se rechaza sin tocar la dimension de cobro:
     * el documento sigue en cartera y se cobrara en la siguiente fecha.
     */
    @Test
    void anExchangeInvoiceCannotBeCollectedOnceTheDayIsClosed() {
        Workshop workshop = authenticatedWorkshop();
        LocalDate today = LocalDate.now(clock);
        Sale exchange = pendingExchangeInvoice(workshop, today, "10000.00");

        service.close(arqueo("0.00", "0.00"));

        assertThatThrownBy(() -> saleService.registerPayment(
                exchange.getId(), new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cerrada");

        Sale stored = reload(exchange.getId());
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getPaidAt()).isNull();
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);
    }

    @Test
    void collectingBeforeTheClosureStillBehavesExactlyAsBefore() {
        Workshop workshop = authenticatedWorkshop();
        Sale exchange = pendingExchangeInvoice(
                workshop, LocalDate.now(clock), "10000.00");

        saleService.registerPayment(
                exchange.getId(), new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER));

        Sale stored = reload(exchange.getId());
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(stored.getPaidAt()).isNotNull();
    }

    // ── Aislamiento y dia siguiente ──────────────────────────────

    /** El cierre de un taller no puede paralizar la facturacion de otro. */
    @Test
    void theClosureOfOneWorkshopBlocksNeitherInvoicingNorCollectingInAnother() {
        authenticatedWorkshop();
        service.close(arqueo("0.00", "0.00"));

        Workshop open = authenticatedWorkshop();
        PendingSale pending = pendingSaleWithWork(open);
        Sale exchange = pendingExchangeInvoice(open, LocalDate.now(clock), "500.00");

        saleService.completeSale(pending.id(), invoiceCash());
        saleService.registerPayment(
                exchange.getId(), new RegisterPaymentRequestDTO(PaymentMethod.CASH));

        assertThat(reload(pending.id()).getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(reload(exchange.getId()).getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    /** Cerrar el 20 no bloquea el 21: las tres operaciones vuelven a estar vivas. */
    @Test
    void theNextBusinessDayAllowsCreatingInvoicingAndCollectingAgain() {
        Workshop workshop = authenticatedWorkshop();
        Customer client = customer();

        clock.moveTo(DAY_ONE);
        PendingSale pending = pendingSaleWithWork(workshop);
        Sale exchange = pendingExchangeInvoice(workshop, DAY_ONE, "800.00");
        service.close(arqueo("0.00", "0.00"));

        clock.moveTo(DAY_TWO);
        assertThat(saleService.create(new CreateSaleRequestDTO(client.getId(), null)).id())
                .isNotNull();
        saleService.completeSale(pending.id(), invoiceCash());
        saleService.registerPayment(
                exchange.getId(), new RegisterPaymentRequestDTO(PaymentMethod.CASH));

        assertThat(reload(pending.id()).getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(reload(exchange.getId()).getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    /**
     * Frontera del dia. El guard y los sellos de la venta salen del MISMO reloj,
     * asi que no puede ocurrir que se valide contra el dia D y se estampe D+1.
     */
    @Test
    void theGuardAndTheSaleTimestampsNeverDisagreeAcrossTheDayBoundary() {
        Workshop workshop = authenticatedWorkshop();
        LocalDate closedDay = LocalDate.of(2026, 8, 20);
        LocalDate nextDay = closedDay.plusDays(1);

        clock.moveTo(closedDay.atTime(23, 59, 59));
        service.close(arqueo("0.00", "0.00"));
        PendingSale pending = pendingSaleWithWork(workshop);

        // Ultimo segundo del dia cerrado: rechazado.
        assertThatThrownBy(() -> saleService.completeSale(pending.id(), invoiceCash()))
                .isInstanceOf(ValidationException.class);

        // Un segundo despues ya es otra fecha operativa, sin cierre propio.
        clock.moveTo(nextDay.atStartOfDay());
        saleService.completeSale(pending.id(), invoiceCash());

        Sale stored = reload(pending.id());
        assertThat(stored.getCompletedAt().toLocalDate()).isEqualTo(nextDay);
        assertThat(stored.getPaidAt().toLocalDate()).isEqualTo(nextDay);
    }

    // ── Concurrencia ─────────────────────────────────────────────

    /**
     * La carrera entre cerrar caja y crear venta.
     *
     * El hilo del cierre mantiene su transaccion abierta despues de insertar el
     * snapshot; mientras tanto, el hilo de la venta intenta crear una. Con solo
     * un `exists(...)` la venta no veria el cierre sin confirmar y se
     * persistiria despues de el; con el cerrojo de la fila del taller, la venta
     * espera al commit y entonces encuentra la caja cerrada.
     *
     * Sea cual sea el orden real de los dos hilos, el resultado tiene que ser el
     * mismo: cierre confirmado y NINGUNA venta nueva.
     */
    @Test
    void aSaleCannotBePersistedAfterTheClosureOfTheSameDayIsConfirmed() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        Customer client = customer();
        long salesBefore = saleRepository.count();

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> service.close(arqueo("0.00", "0.00")),
                () -> saleService.create(new CreateSaleRequestDTO(client.getId(), null)));

        assertThat(failure).isInstanceOf(ValidationException.class);
        assertThat(saleRepository.count()).isEqualTo(salesBefore);
        assertThat(repository.findByWorkshopIdAndBusinessDate(
                workshop.getId(), LocalDate.now(clock))).isPresent();
    }


    /**
     * El motor de todas las pruebas de carrera de este archivo.
     *
     * Ejecuta `holder` dentro de una transaccion que se mantiene ABIERTA —con el
     * cerrojo del taller tomado— y, mientras la sostiene, lanza `contender` en
     * otro hilo. Cuando el contendiente lleva medio segundo esperando, se deja
     * confirmar al primero.
     *
     * Fuerza asi un orden concreto de los dos posibles, que es lo unico que
     * permite comprobar por separado "gana la operacion financiera" y "gana el
     * cierre" en vez de dejarlo al azar del planificador.
     *
     * @return el fallo del contendiente, o null si termino bien.
     */
    private Throwable raceAgainstHeldTransaction(
            UserAccount account,
            Workshop workshop,
            Supplier<?> holder,
            Runnable contender) throws Exception {

        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            Future<?> held = threads.submit(() -> template.execute(status -> {
                authenticateThread(account, workshop);
                Object result = holder.get();
                holderReady.countDown();
                awaitQuietly(releaseHolder);
                return result;
            }));

            assertThat(holderReady.await(15, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> other = threads.submit(() -> {
                authenticateThread(account, workshop);
                try {
                    contender.run();
                    return null;
                } catch (Throwable failure) {
                    return failure;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });

            // Tiempo para que el contendiente llegue al cerrojo y se quede
            // esperando; despues se deja confirmar al que lo sostiene.
            TimeUnit.MILLISECONDS.sleep(500);
            releaseHolder.countDown();

            held.get(20, TimeUnit.SECONDS);
            return other.get(20, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
    }

    private void authenticateThread(UserAccount account, Workshop workshop) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokenFor(account, workshop)));
    }

    // ── Carrera: cierre contra emitir el documento ───────────────

    /**
     * Gana el CIERRE. La emision se queda esperando el cerrojo, despierta con el
     * snapshot ya confirmado y se rechaza. La venta sigue abierta y el snapshot
     * no queda obsoleto.
     */
    @Test
    void whenTheClosureWinsTheLockInvoicingIsRejectedAndTheSaleStaysOpen()
            throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        PendingSale pending = pendingSaleWithWork(workshop);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> service.close(arqueo("0.00", "0.00")),
                () -> saleService.completeSale(pending.id(), invoiceCash()));

        assertThat(failure).isInstanceOf(ValidationException.class);

        Sale stored = reload(pending.id());
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(stored.getCompletedAt()).isNull();
        assertThat(statusOf(pending.workResultId())).isEqualTo(WorkResultStatus.PENDING);

        CashClosureResponseDTO snapshot = service.getByDate(LocalDate.now(clock));
        assertThat(snapshot.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
    }

    /**
     * Gana la EMISION. El cierre espera, despierta y recalcula: el snapshot
     * incluye la factura, con su importe exacto, tanto en facturacion como en
     * efectivo cobrado.
     */
    @Test
    void whenInvoicingWinsTheLockTheSnapshotIncludesIt() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        PendingSale pending = pendingSaleWithWork(workshop);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.completeSale(pending.id(), invoiceCash()),
                () -> service.close(arqueo("0.00", pending.total().toPlainString())));

        assertThat(failure).as("el cierre debe poder confirmarse").isNull();
        assertThat(reload(pending.id()).getStatus()).isEqualTo(SaleStatus.COMPLETED);

        CashClosureResponseDTO snapshot = service.getByDate(LocalDate.now(clock));
        assertThat(snapshot.invoicing().totalInvoiced())
                .isEqualByComparingTo(pending.total());
        assertThat(snapshot.collections().cashAmount())
                .isEqualByComparingTo(pending.total());
        assertThat(snapshot.cashReconciliation().expectedCash())
                .isEqualByComparingTo(pending.total());
        assertThat(snapshot.cashReconciliation().difference())
                .isEqualByComparingTo("0.00");
    }

    // ── Carrera: cierre contra cobrar una cambiaria ──────────────

    /** Gana el CIERRE: el cobro se rechaza y la cambiaria sigue pendiente. */
    @Test
    void whenTheClosureWinsTheLockCollectingIsRejectedAndTheInvoiceStaysPending()
            throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        Sale exchange = pendingExchangeInvoice(
                workshop, LocalDate.now(clock), "10000.00");

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> service.close(arqueo("0.00", "0.00")),
                () -> saleService.registerPayment(
                        exchange.getId(),
                        new RegisterPaymentRequestDTO(PaymentMethod.CASH)));

        assertThat(failure).isInstanceOf(ValidationException.class);

        Sale stored = reload(exchange.getId());
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getPaidAt()).isNull();

        CashClosureResponseDTO snapshot = service.getByDate(LocalDate.now(clock));
        assertThat(snapshot.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(snapshot.receivables().pendingAmount()).isEqualByComparingTo("10000.00");
    }

    /**
     * Gana el COBRO. Se cobra EN EFECTIVO a proposito: asi el snapshot no solo
     * tiene que incluirlo en `totalCollected`, sino tambien en `cashPayments`,
     * en `expectedCash` y por tanto en la diferencia del arqueo. Si el cierre no
     * viera el cobro, la caja saldria con Q10,000 de sobrante.
     */
    @Test
    void whenACashCollectionWinsTheLockItReachesTheArqueoOfTheSnapshot()
            throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        Sale exchange = pendingExchangeInvoice(
                workshop, LocalDate.now(clock), "10000.00");

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.registerPayment(
                        exchange.getId(),
                        new RegisterPaymentRequestDTO(PaymentMethod.CASH)),
                () -> service.close(arqueo("500.00", "10500.00")));

        assertThat(failure).as("el cierre debe poder confirmarse").isNull();

        CashClosureResponseDTO snapshot = service.getByDate(LocalDate.now(clock));
        assertThat(snapshot.collections().cashAmount()).isEqualByComparingTo("10000.00");
        assertThat(snapshot.collections().totalCollected())
                .isEqualByComparingTo("10000.00");
        assertThat(snapshot.receivables().collectedAmount())
                .isEqualByComparingTo("10000.00");
        assertThat(snapshot.receivables().pendingAmount()).isEqualByComparingTo("0.00");
        assertThat(snapshot.cashReconciliation().expectedCash())
                .isEqualByComparingTo("10500.00");
        assertThat(snapshot.cashReconciliation().difference())
                .isEqualByComparingTo("0.00");
    }

    // ── Carrera: dos operaciones financieras entre si ───────────

    /**
     * Dos emisiones simultaneas de la MISMA venta.
     *
     * El cerrojo del taller solo serializa desde que se adquiere: si la venta se
     * lee ANTES, el segundo hilo entra en la seccion critica con una entidad ya
     * cargada que dice PENDING y vuelve a emitir un documento ya emitido,
     * pisando metodo de pago y fecha del primero.
     *
     * La venta no tiene WorkResult a proposito: con el, `markAsSold` rechaza la
     * segunda emision por su cuenta y la carrera real queda tapada.
     */
    @Test
    void twoSimultaneousInvoicingsCannotCompleteTheSameSaleTwice() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        UUID saleId = pendingSaleWithoutWork(workshop);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.completeSale(saleId, invoiceCash()),
                () -> saleService.completeSale(
                        saleId,
                        new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CARD)));

        assertThat(failure)
                .as("la segunda emision debe encontrar la venta ya cerrada")
                .isInstanceOf(ValidationException.class);
        assertThat(failure).hasMessageContaining("La venta no se encuentra abierta");

        Sale stored = reload(saleId);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        // El documento es el del ganador: nada de last-writer-wins.
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    /** El trabajo pendiente se vende UNA sola vez aunque dos emisiones compitan. */
    @Test
    void aWorkResultIsSoldOnlyOnceWhenTwoInvoicingsRaceForTheSameSale() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        PendingSale pending = pendingSaleWithWork(workshop);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.completeSale(pending.id(), invoiceCash()),
                () -> saleService.completeSale(
                        pending.id(),
                        new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CARD)));

        assertThat(failure).isInstanceOf(ValidationException.class);

        Sale stored = reload(pending.id());
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(statusOf(pending.workResultId())).isEqualTo(WorkResultStatus.SOLD);
    }

    /**
     * Dos cobros simultaneos de la MISMA cambiaria.
     *
     * El perdedor mueve el reloj antes de intentarlo: si llegara a cobrar,
     * `paidAt` cambiaria de instante ademas de metodo, y el arqueo del dia
     * contaria un ingreso que nunca existio dos veces.
     */
    @Test
    void twoSimultaneousCollectionsCannotPayTheSameExchangeInvoiceTwice() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        Sale exchange = pendingExchangeInvoice(
                workshop, LocalDate.now(clock), "10000.00");
        LocalDateTime winnerMoment = LocalDateTime.now(clock);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.registerPayment(
                        exchange.getId(),
                        new RegisterPaymentRequestDTO(PaymentMethod.CASH)),
                () -> {
                    clock.moveTo(winnerMoment.plusHours(1));
                    saleService.registerPayment(
                            exchange.getId(),
                            new RegisterPaymentRequestDTO(PaymentMethod.CARD));
                });

        assertThat(failure)
                .as("el segundo cobro debe encontrar la factura ya pagada")
                .isInstanceOf(ValidationException.class);
        assertThat(failure).hasMessageContaining("La venta ya fue pagada");

        Sale stored = reload(exchange.getId());
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(stored.getPaidAt()).isEqualTo(winnerMoment);
    }

    /** Dos cierres simultaneos del mismo dia: uno gana, el otro falla. */
    @Test
    void twoSimultaneousClosuresOfTheSameDayProduceExactlyOneSnapshot() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        LocalDate businessDate = LocalDate.now(clock);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            List<Future<Throwable>> attempts = List.of(
                    threads.submit(() -> attemptClose(account, workshop, ready)),
                    threads.submit(() -> attemptClose(account, workshop, ready)));

            long failures = attempts.stream()
                    .map(CashClosureServiceDatabaseTest::resultOf)
                    .filter(failure -> failure != null)
                    .count();

            assertThat(failures).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }

        assertThat(repository
                .findByWorkshopIdAndBusinessDateBetweenOrderByBusinessDateDescIdDesc(
                        workshop.getId(), businessDate, businessDate))
                .hasSize(1);
    }

    private Throwable attemptClose(
            UserAccount account, Workshop workshop, CountDownLatch ready) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokenFor(account, workshop)));
        ready.countDown();
        awaitQuietly(ready);
        try {
            service.close(arqueo("0.00", "0.00"));
            return null;
        } catch (Throwable failure) {
            return failure;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Throwable resultOf(Future<Throwable> attempt) {
        try {
            return attempt.get(20, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Auditoria del cierre ─────────────────────────────────────

    /** `createdAt` ES el instante del cierre; no hay una segunda columna. */
    @Test
    void theClosureTimestampIsTheAuditCreationTimestamp() {
        Workshop workshop = authenticatedWorkshop();
        LocalDateTime before = LocalDateTime.now();

        CashClosureResponseDTO closed = service.close(arqueo("0.00", "0.00"));

        var stored = repository.findById(closed.id()).orElseThrow();
        // Se comparan truncados al milisegundo: PostgreSQL guarda TIMESTAMP con
        // precision de microsegundos y la instancia recien construida conserva
        // los nanosegundos del reloj de la JVM. Lo que se comprueba es que sea
        // el MISMO instante, no que sobreviva una precision que la columna no
        // tiene.
        assertThat(closed.closedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(stored.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
        assertThat(stored.getCreatedAt()).isAfterOrEqualTo(before.minusMinutes(1));
        assertThat(stored.getWorkshop().getId()).isEqualTo(workshop.getId());
        assertThat(stored.getClosedBy()).isNotNull();
    }

}
