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
import com.cns.spring_house.cashclosure.dto.CashClosureMonthStatus;
import com.cns.spring_house.cashclosure.dto.CashClosureMonthlyResponseDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
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
import com.cns.spring_house.sale.dto.RefundRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
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
    /** Septiembre: el mes SIGUIENTE, para las devoluciones a caballo de dos meses. */
    private static final LocalDate NEXT_MONTH_DAY = LocalDate.of(2026, 9, 2);

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

    /** Lo cobrado en efectivo dentro de un cierre, leido del desglose. */
    private static BigDecimal cashOf(CashClosureResponseDTO closure) {
        return closure.collections().methods().stream()
                .filter(method -> method.method() == PaymentMethod.CASH)
                .findFirst()
                .map(ClosurePaymentMethodDTO::amount)
                .orElseThrow();
    }

    /**
     * Cierra la caja de un dia. No recibe cifras: el cierre las calcula todas
     * contra la base, que es justo lo que hace imposible confirmarlo con
     * numeros inventados.
     */
    /** Revierte la venta devolviendo dinero, con el motivo de siempre. */
    private void refund(Sale sale, String amount, PaymentMethod method) {
        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                "Devolucion al cliente",
                new RefundRequestDTO(new BigDecimal(amount), method)));
    }

    /** Lo devuelto por una forma concreta dentro de un mes. */
    private static BigDecimal monthRefundOf(
            CashClosureMonthlyResponseDTO month, PaymentMethod method) {
        return month.refunds().methods().stream()
                .filter(row -> row.method() == method)
                .findFirst()
                .map(com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO::amount)
                .orElseThrow();
    }

    /** Lo devuelto por una forma concreta dentro de un cierre. */
    private static BigDecimal refundOf(
            CashClosureResponseDTO closure, PaymentMethod method) {
        return closure.refunds().methods().stream()
                .filter(row -> row.method() == method)
                .findFirst()
                .map(com.cns.spring_house.cashclosure.dto.ClosureRefundMethodDTO::amount)
                .orElseThrow();
    }

    private CashClosureResponseDTO closeOn(LocalDate day) {
        clock.moveTo(day);
        return service.close();
    }

    // ── Snapshot inmutable ───────────────────────────────────────



    // ── Un cierre por taller y dia ───────────────────────────────




    // ── Rango ────────────────────────────────────────────────────







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

        service.close();

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
        service.close();

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
        service.close();

        assertThatThrownBy(() -> saleService.create(
                new CreateSaleRequestDTO(client.getId(), null)))
                .isInstanceOf(ValidationException.class);

        clock.moveTo(DAY_TWO);
        var sale = saleService.create(new CreateSaleRequestDTO(client.getId(), null));

        assertThat(sale.id()).isNotNull();
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

        service.close();

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

        service.close();

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
        service.close();

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
        service.close();

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
        service.close();
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
                () -> service.close(),
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
                () -> service.close(),
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
                () -> service.close());

        assertThat(failure).as("el cierre debe poder confirmarse").isNull();
        assertThat(reload(pending.id()).getStatus()).isEqualTo(SaleStatus.COMPLETED);

        CashClosureResponseDTO snapshot = service.getByDate(LocalDate.now(clock));
        assertThat(snapshot.invoicing().totalInvoiced())
                .isEqualByComparingTo(pending.total());
        // Y el dinero aparece donde debe: en el desglose por forma de pago, que
        // suma exactamente el total cobrado.
        assertThat(cashOf(snapshot)).isEqualByComparingTo(pending.total());
        assertThat(snapshot.collections().totalCollected())
                .isEqualByComparingTo(pending.total());
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
                () -> service.close(),
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
        // La cambiaria sigue emitida y sin cobrar dentro del propio periodo.
        assertThat(snapshot.exchange().issuedPendingAmount())
                .isEqualByComparingTo("10000.00");
    }

    /**
     * Gana el COBRO. Se cobra EN EFECTIVO a proposito: asi el snapshot no solo
     * tiene que incluirlo en el total cobrado, sino tambien en la fila de
     * efectivo del desglose. Si el cierre no viera el cobro, el dia quedaria
     * archivado con Q10,000 menos de los que de verdad entraron.
     */
    @Test
    void whenACashCollectionWinsTheLockItReachesTheSnapshot()
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
                () -> service.close());

        assertThat(failure).as("el cierre debe poder confirmarse").isNull();

        CashClosureResponseDTO snapshot = service.getByDate(LocalDate.now(clock));
        assertThat(snapshot.collections().totalCollected())
                .isEqualByComparingTo("10000.00");
        assertThat(cashOf(snapshot)).isEqualByComparingTo("10000.00");
        // La cambiaria figura como cobrada en el periodo...
        assertThat(snapshot.exchange().collectedAmount())
                .isEqualByComparingTo("10000.00");
        // ...y ya no queda nada pendiente de lo emitido ese dia.
        assertThat(snapshot.exchange().issuedPendingAmount())
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

    // ── Devolucion contra cierre: la carrera ─────────────────────
    //
    // Devolver dinero mueve la caja, asi que compite por el MISMO cerrojo de
    // taller/fecha que cobrar y que cerrar. No se introduce un segundo orden de
    // bloqueo: reutilizar el que ya existe es lo que garantiza que no haya
    // interbloqueo entre cobrar, devolver y cerrar.

    /**
     * Si el cierre gana la carrera, la devolucion se rechaza ENTERA.
     *
     * Lo que importa no es solo que falle, sino que no deje rastro: la venta
     * sigue COMPLETED y el stock sin restaurar. Una devolucion rechazada que
     * hubiera revertido la venta seria el peor resultado posible.
     */
    @Test
    void whenTheClosureWinsTheLockTheRefundIsRejectedAndNothingIsMutated()
            throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());

        // El cobro vive en un dia ya cerrado; hoy solo se devuelve.
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);
        clock.moveTo(DAY_THREE);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                service::close,
                () -> saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                        "Devolucion tardia",
                        new RefundRequestDTO(new BigDecimal("1000.00"), PaymentMethod.CASH))));

        assertThat(failure)
                .as("la devolucion no puede entrar en un dia ya cerrado")
                .isInstanceOf(ValidationException.class);

        Sale stored = reload(sale.getId());
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);

        CashClosureResponseDTO snapshot = service.getByDate(DAY_THREE);
        assertThat(snapshot.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(snapshot.netPeriod())
                .isEqualByComparingTo(snapshot.collections().totalCollected());
    }

    /**
     * Si la devolucion gana, entra en el snapshot que se confirma despues.
     *
     * El invariante de las dos pruebas juntas: la devolucion esta DENTRO del
     * cierre o no ocurrio. Nunca fuera de el.
     */
    @Test
    void whenARefundWinsTheLockItReachesTheSnapshot() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);
        clock.moveTo(DAY_THREE);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                        "Devolucion",
                        new RefundRequestDTO(new BigDecimal("400.00"), PaymentMethod.CASH))),
                () -> service.close());

        assertThat(failure).as("el cierre posterior debe poder confirmarse").isNull();

        CashClosureResponseDTO snapshot = service.getByDate(DAY_THREE);
        assertThat(snapshot.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(snapshot.netPeriod()).isEqualByComparingTo("-400.00");
    }

    /**
     * Dos devoluciones simultaneas de la misma venta: una sola sobrevive.
     *
     * Es lo que impide devolver dos veces lo mismo por un doble clic o un
     * reintento. La segunda, ya serializada por el cerrojo del dia, lee la
     * venta REVERTIDA y responde un error de negocio legible en vez de chocar
     * contra un indice unico.
     */
    @Test
    void twoSimultaneousRefundsCannotReturnTheSameMoneyTwice() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);
        clock.moveTo(DAY_THREE);

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                        "Primera",
                        new RefundRequestDTO(new BigDecimal("1000.00"), PaymentMethod.CASH))),
                () -> saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                        "Segunda",
                        new RefundRequestDTO(new BigDecimal("1000.00"), PaymentMethod.CASH))));

        assertThat(failure)
                .as("la segunda devolucion debe encontrar la venta ya revertida")
                .isInstanceOf(ValidationException.class);

        CashClosureResponseDTO today = closeOn(DAY_THREE);
        assertThat(today.refunds().refundCount()).isEqualTo(1L);
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("1000.00");
    }

    /**
     * La carrera del MISMO dia, que es la que el arreglo vuelve interesante.
     *
     * El cobro y la devolucion ocurren en el dia que se esta cerrando. Si la
     * devolucion gana, el snapshot tiene que contener las dos mitades del
     * movimiento —Q1,000 dentro y Q400 fuera— y no una sola.
     */
    @Test
    void whenASameDayRefundWinsTheLockBothHalvesReachTheSnapshot() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        LocalDate today = LocalDate.now(clock);

        Sale sale = invoice(workshop, today, PaymentMethod.CASH, "1000.00");

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                () -> saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                        "Devolucion parcial",
                        new RefundRequestDTO(new BigDecimal("400.00"), PaymentMethod.CASH))),
                () -> service.close());

        assertThat(failure).as("el cierre posterior debe confirmarse").isNull();

        CashClosureResponseDTO snapshot = service.getByDate(today);
        assertThat(snapshot.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(snapshot.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(snapshot.netPeriod()).isEqualByComparingTo("600.00");
    }

    /**
     * Y si gana el cierre, la devolucion del mismo dia se rechaza ENTERA.
     *
     * El cobro sigue dentro del snapshot —ocurrio antes— pero no hay ninguna
     * salida, porque la devolucion no llego a existir. La venta tampoco quedo
     * revertida: se valida antes de mutar.
     */
    @Test
    void whenTheClosureWinsTheSameDayRefundIsRejectedButTheCollectionStays()
            throws Exception {
        Workshop workshop = authenticatedWorkshop();
        UserAccount account = accounts.get(workshop.getId());
        LocalDate today = LocalDate.now(clock);

        Sale sale = invoice(workshop, today, PaymentMethod.CASH, "1000.00");

        Throwable failure = raceAgainstHeldTransaction(
                account, workshop,
                service::close,
                () -> saleService.reverse(sale.getId(), new ReverseSaleRequestDTO(
                        "Tarde",
                        new RefundRequestDTO(new BigDecimal("400.00"), PaymentMethod.CASH))));

        assertThat(failure).isInstanceOf(ValidationException.class);
        assertThat(reload(sale.getId()).getStatus()).isEqualTo(SaleStatus.COMPLETED);

        CashClosureResponseDTO snapshot = service.getByDate(today);
        assertThat(snapshot.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(snapshot.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(snapshot.netPeriod()).isEqualByComparingTo("1000.00");
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

        // Y en la base queda EXACTAMENTE un snapshot de ese dia.
        assertThat(repository.findClosedDates(
                workshop.getId(), businessDate, businessDate)).hasSize(1);
    }

    private Throwable attemptClose(
            UserAccount account, Workshop workshop, CountDownLatch ready) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokenFor(account, workshop)));
        ready.countDown();
        awaitQuietly(ready);
        try {
            service.close();
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

        CashClosureResponseDTO closed = service.close();

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


    // ── Snapshot inmutable ───────────────────────────────────────

    /**
     * Lo que define al snapshot: despues de cerrar, los datos de origen pueden
     * cambiar —se cobra una cambiaria pendiente, se emite otra factura— y el
     * documento historico sigue diciendo exactamente lo que se confirmo.
     *
     * Es el ejemplo del lunes y el miercoles: la cambiaria del lunes queda
     * registrada como PENDIENTE en el cierre del lunes, y cobrarla el miercoles
     * no reescribe ese documento.
     */
    @Test
    void aConfirmedClosureNeverChangesWhenAnOldExchangeIsCollectedLater() {
        Workshop workshop = authenticatedWorkshop();

        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "5000.00");
        Sale exchange = pendingExchangeInvoice(workshop, DAY_ONE, "2000.00");

        CashClosureResponseDTO monday = closeOn(DAY_ONE);

        assertThat(monday.collections().totalCollected()).isEqualByComparingTo("5000.00");
        assertThat(monday.exchange().issuedPendingAmount()).isEqualByComparingTo("2000.00");
        assertThat(monday.exchange().issued()).singleElement()
                .satisfies(invoice -> assertThat(invoice.collected()).isFalse());

        // Miercoles: se cobra la cambiaria del lunes.
        clock.moveTo(DAY_THREE);
        collect(exchange, DAY_THREE, PaymentMethod.TRANSFER);

        // El cierre del lunes se relee y NO ha cambiado ni un quetzal.
        CashClosureResponseDTO reread = service.getByDate(DAY_ONE);

        assertThat(reread.collections().totalCollected()).isEqualByComparingTo("5000.00");
        assertThat(reread.exchange().issuedPendingAmount()).isEqualByComparingTo("2000.00");
        assertThat(reread.exchange().issued()).singleElement()
                .satisfies(invoice -> {
                    assertThat(invoice.collected()).isFalse();
                    assertThat(invoice.paidAt()).isNull();
                    assertThat(invoice.paymentMethod()).isNull();
                });
        assertThat(reread.itemsBilledAmount())
                .isEqualByComparingTo(monday.itemsBilledAmount());
    }

    /** Y el dinero aparece en el dia en que de verdad se cobro. */
    @Test
    void theLaterCollectionBelongsToTheDayItWasActuallyCollected() {
        Workshop workshop = authenticatedWorkshop();
        Sale exchange = pendingExchangeInvoice(workshop, DAY_ONE, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        collect(exchange, DAY_THREE, PaymentMethod.TRANSFER);
        CashClosureResponseDTO wednesday = closeOn(DAY_THREE);

        assertThat(wednesday.collections().totalCollected())
                .isEqualByComparingTo("1000.00");
        assertThat(transferOf(wednesday)).isEqualByComparingTo("1000.00");
        // Se cobro el miercoles pero NO se emitio el miercoles.
        assertThat(wednesday.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(wednesday.exchange().collectedAmount()).isEqualByComparingTo("1000.00");
        assertThat(wednesday.exchange().previousCollectedAmount())
                .isEqualByComparingTo("1000.00");
        assertThat(wednesday.exchange().collected()).singleElement()
                .satisfies(invoice -> assertThat(invoice.collected()).isTrue());
    }

    // ── Reversion despues del cierre ─────────────────────────────
    //
    // Revertir NO es reembolsar. `SaleServiceImpl.reverse` deshace lo que la
    // venta reservo —existencias, lotes FIFO, WorkResult— y deja constancia en
    // `SaleAction`, pero no toca `paymentStatus`, `paidAt` ni `paymentMethod`,
    // y no existe en el dominio ninguna entidad de reembolso, nota de credito
    // ni movimiento de caja negativo.
    //
    // Estas pruebas fijan esa frontera. La restauracion de existencias y de
    // lotes ya esta cubierta en SaleServiceDatabaseTest e
    // InventoryAcquisitionFifoDatabaseTest; aqui solo se prueba lo que NADIE
    // cubria: que pasa con el dinero ya cerrado.

    /**
     * El caso del enunciado: se cobran Q1,000 el lunes, se cierra el lunes y el
     * miercoles se revierte esa venta.
     *
     * El lunes SIGUE diciendo Q1,000. No porque nadie se atreva a tocarlo, sino
     * porque es estructuralmente imposible: el calculador solo se ejecuta para
     * la fecha operativa en curso —en `getPreview` y en `close`—, y un cierre
     * confirmado se LEE de sus tablas hijas. Ninguna ruta lo recalcula.
     */
    @Test
    void aReversalAfterTheCloseNeverRewritesTheConfirmedSnapshot() {
        Workshop workshop = authenticatedWorkshop();

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        CashClosureResponseDTO monday = closeOn(DAY_ONE);

        assertThat(monday.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(cashOf(monday)).isEqualByComparingTo("1000.00");

        // Miercoles: se revierte la venta del lunes. El cierre NO lo impide:
        // `reverse` no consulta el guardia de caja, a diferencia de crear,
        // facturar y cobrar. Se documenta el comportamiento real.
        clock.moveTo(DAY_THREE);
        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO("Devolucion del cliente"));

        assertThat(saleRepository.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(SaleStatus.REVERSED);

        CashClosureResponseDTO reread = service.getByDate(DAY_ONE);

        assertThat(reread.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(cashOf(reread)).isEqualByComparingTo("1000.00");
        assertThat(reread.invoicing().totalInvoiced())
                .isEqualByComparingTo(monday.invoicing().totalInvoiced());
        assertThat(reread.itemsBilledAmount())
                .isEqualByComparingTo(monday.itemsBilledAmount());
    }

    /**
     * Y el estado de cobro de la venta revertida queda INTACTO.
     *
     * Es la prueba de que este sistema no modela la salida de dinero: si
     * revertir reembolsara, lo minimo seria devolver la venta a PENDING y
     * limpiar `paidAt`. No lo hace, y esta prueba lo fija para que nadie lo
     * cambie creyendo que corrige un olvido.
     */
    @Test
    void reversingAfterTheCloseLeavesThePaymentStateUntouched() {
        Workshop workshop = authenticatedWorkshop();

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO("Devolucion del cliente"));

        Sale reversed = saleRepository.findById(sale.getId()).orElseThrow();

        assertThat(reversed.getStatus()).isEqualTo(SaleStatus.REVERSED);
        assertThat(reversed.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reversed.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(reversed.getPaidAt()).isEqualTo(DAY_ONE.atTime(10, 0));
        assertThat(reversed.getCompletedAt()).isEqualTo(DAY_ONE.atTime(10, 0));
    }

    /**
     * Y en el periodo en que se revierte NO aparece nada.
     *
     * Ni un cobro negativo ni una devolucion: el miercoles cierra en cero y el
     * mes sigue sumando los Q1,000 del lunes. Inventar aqui un -Q1,000 seria
     * fabricar un movimiento de caja que nunca ocurrio; lo correcto es que el
     * hueco se vea, y este es el test que impide taparlo.
     */
    @Test
    void aReversedSaleProducesNoNegativeMovementInAnyLaterPeriod() {
        Workshop workshop = authenticatedWorkshop();

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO("Devolucion del cliente"));

        CashClosureResponseDTO wednesday = closeOn(DAY_THREE);

        assertThat(wednesday.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(wednesday.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(wednesday.items()).isEmpty();
        assertThat(wednesday.collections().methods())
                .allSatisfy(method ->
                        assertThat(method.amount()).isGreaterThanOrEqualTo(BigDecimal.ZERO));

        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        // El mes conserva el lunes: la reversion no reescribe el pasado ni
        // resta en el presente. El dinero cobrado sigue contado UNA vez.
        assertThat(month.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(cashOf(month.collections())).isEqualByComparingTo("1000.00");
    }

    // ── Devoluciones: el dinero que sale ─────────────────────────
    //
    // Revertir y devolver son cosas distintas y el sistema las registra por
    // separado. Estas pruebas fijan las tres cifras que el taller lee —cuanto
    // entro, cuanto salio y con cuanto se queda— y la regla que las ordena:
    // la devolucion pertenece al dia en que EL DINERO salio.

    /** Lo cobrado sigue siendo lo cobrado; lo devuelto se cuenta aparte. */
    @Test
    void theDayReportsCollectedRefundedAndNetSeparately() {
        Workshop workshop = authenticatedWorkshop();

        // Ayer se cobro una venta que hoy se devuelve.
        Sale old = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1500.00");
        closeOn(DAY_ONE);

        // Hoy: Q5,000 en facturas normales y Q1,000 de una cambiaria antigua.
        clock.moveTo(DAY_THREE);
        invoice(workshop, DAY_THREE, PaymentMethod.CASH, "5000.00");
        Sale exchange = pendingExchangeInvoice(workshop, DAY_TWO, "1000.00");
        collect(exchange, DAY_THREE, PaymentMethod.CASH);

        // Y se devuelven Q1,500 en efectivo por la venta de anteayer.
        refund(old, "1500.00", PaymentMethod.CASH);

        CashClosureResponseDTO today = closeOn(DAY_THREE);

        assertThat(today.collections().totalCollected()).isEqualByComparingTo("6000.00");
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("1500.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("4500.00");
        assertThat(today.refunds().refundCount()).isEqualTo(1L);

        // Y lo emitido a credito y sin cobrar no toca ninguna de las dos.
        assertThat(today.exchange().issuedPendingAmount()).isEqualByComparingTo("0.00");
    }

    /** El desglose por forma de devolucion suma EXACTAMENTE lo devuelto. */
    @Test
    void refundMethodsReconcileToTheRefundedTotal() {
        Workshop workshop = authenticatedWorkshop();

        Sale one = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "500.00");
        Sale two = invoice(workshop, DAY_ONE, PaymentMethod.CARD, "300.00");
        Sale three = invoice(workshop, DAY_ONE, PaymentMethod.TRANSFER, "200.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        refund(one, "500.00", PaymentMethod.CASH);
        refund(two, "300.00", PaymentMethod.CARD);
        refund(three, "200.00", PaymentMethod.TRANSFER);

        CashClosureResponseDTO today = closeOn(DAY_THREE);

        assertThat(refundOf(today, PaymentMethod.CASH)).isEqualByComparingTo("500.00");
        assertThat(refundOf(today, PaymentMethod.CARD)).isEqualByComparingTo("300.00");
        assertThat(refundOf(today, PaymentMethod.TRANSFER)).isEqualByComparingTo("200.00");
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("1000.00");

        // Sin cobros hoy, el dia cierra en NEGATIVO. Es informacion, no un error.
        assertThat(today.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("-1000.00");
    }

    /**
     * El caso completo del enunciado: el lunes no cambia, el miercoles registra
     * la salida.
     */
    @Test
    void theOriginalDayKeepsItsMoneyAndTheRefundBelongsToTheDayItLeft() {
        Workshop workshop = authenticatedWorkshop();

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        CashClosureResponseDTO monday = closeOn(DAY_ONE);

        assertThat(monday.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(monday.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(monday.netPeriod()).isEqualByComparingTo("1000.00");

        clock.moveTo(DAY_THREE);
        refund(sale, "1000.00", PaymentMethod.CASH);

        // El lunes se relee y NO ha cambiado: sigue diciendo que entraron Q1,000.
        CashClosureResponseDTO reread = service.getByDate(DAY_ONE);
        assertThat(reread.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(reread.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(reread.netPeriod()).isEqualByComparingTo("1000.00");

        // Y el miercoles registra la salida, con su detalle legible.
        CashClosureResponseDTO wednesday = closeOn(DAY_THREE);
        assertThat(wednesday.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(wednesday.refunds().totalRefunded()).isEqualByComparingTo("1000.00");
        assertThat(wednesday.netPeriod()).isEqualByComparingTo("-1000.00");
        assertThat(wednesday.refunds().refunds()).singleElement().satisfies(row -> {
            assertThat(row.amount()).isEqualByComparingTo("1000.00");
            assertThat(row.paymentMethod()).isEqualTo(PaymentMethod.CASH);
            assertThat(row.documentCode()).isNotBlank();
            assertThat(row.performedByName()).isNotBlank();
            assertThat(row.reason()).isEqualTo("Devolucion al cliente");
        });
    }

    /** Una venta sin cobrar no tiene dinero que devolver. */
    @Test
    void anUncollectedExchangeInvoiceCannotBeRefunded() {
        Workshop workshop = authenticatedWorkshop();
        Sale exchange = pendingExchangeInvoice(workshop, DAY_ONE, "2000.00");

        clock.moveTo(DAY_ONE);

        assertThatThrownBy(() -> saleService.reverse(exchange.getId(),
                new ReverseSaleRequestDTO("Documento mal emitido",
                        new RefundRequestDTO(new BigDecimal("2000.00"), PaymentMethod.CASH))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no fue cobrada");

        // Y la venta NO quedo revertida: la peticion se rechazo entera.
        assertThat(saleRepository.findById(exchange.getId()).orElseThrow().getStatus())
                .isEqualTo(SaleStatus.COMPLETED);
    }

    /** No se puede devolver mas de lo que entro. */
    @Test
    void aRefundCannotExceedWhatTheSaleCollected() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");

        clock.moveTo(DAY_ONE);

        assertThatThrownBy(() -> saleService.reverse(sale.getId(),
                new ReverseSaleRequestDTO("Devolucion",
                        new RefundRequestDTO(new BigDecimal("1200.00"), PaymentMethod.CASH))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no puede superar");

        assertThat(saleRepository.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(SaleStatus.COMPLETED);
    }

    /** Una devolucion parcial es legitima: se devuelve menos de lo cobrado. */
    @Test
    void aPartialRefundIsAccepted() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        refund(sale, "400.00", PaymentMethod.TRANSFER);

        CashClosureResponseDTO wednesday = closeOn(DAY_THREE);
        assertThat(wednesday.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(refundOf(wednesday, PaymentMethod.TRANSFER))
                .isEqualByComparingTo("400.00");
    }

    /**
     * La forma de devolucion NO se copia de la venta.
     *
     * Se cobro con tarjeta y se devolvio por transferencia porque asi ocurrio.
     * Suponer la forma original habria imputado Q1,000 a una tarjeta por la que
     * no paso nada.
     */
    @Test
    void theRefundChannelIsTheOneUsedToReturnTheMoney() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CARD, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        refund(sale, "1000.00", PaymentMethod.TRANSFER);
        CashClosureResponseDTO wednesday = closeOn(DAY_THREE);

        assertThat(refundOf(wednesday, PaymentMethod.TRANSFER))
                .isEqualByComparingTo("1000.00");
        assertThat(refundOf(wednesday, PaymentMethod.CARD)).isEqualByComparingTo("0.00");
    }

    /** Revertir sin devolver no crea ninguna devolucion. */
    @Test
    void reversingWithoutARefundRecordsNoMoneyOut() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO("Sin devolucion"));

        CashClosureResponseDTO wednesday = closeOn(DAY_THREE);
        assertThat(wednesday.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(wednesday.refunds().refunds()).isEmpty();
        assertThat(wednesday.netPeriod()).isEqualByComparingTo("0.00");
    }

    /** Una devolucion no entra en un periodo ya cerrado. */
    @Test
    void aRefundCannotBePostedIntoAnAlreadyClosedDay() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        // Se intenta devolver EL MISMO dia que ya se cerro.
        assertThatThrownBy(() -> saleService.reverse(sale.getId(),
                new ReverseSaleRequestDTO("Tarde",
                        new RefundRequestDTO(new BigDecimal("1000.00"), PaymentMethod.CASH))))
                .isInstanceOf(ValidationException.class);

        // Y NADA se movio: ni la venta se revirtio ni el stock volvio. Es la
        // razon de validar antes de mutar.
        assertThat(saleRepository.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(SaleStatus.COMPLETED);

        CashClosureResponseDTO monday = service.getByDate(DAY_ONE);
        assertThat(monday.refunds().totalRefunded()).isEqualByComparingTo("0.00");
    }

    /** Revertir SIN devolucion sigue permitido aunque el dia este cerrado. */
    @Test
    void reversingWithoutRefundIsStillAllowedAfterTheClose() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO("Correccion de stock"));

        assertThat(saleRepository.findById(sale.getId()).orElseThrow().getStatus())
                .isEqualTo(SaleStatus.REVERSED);
    }

    /** El cierre de un taller nunca ve las devoluciones de otro. */
    @Test
    void refundsNeverCrossBetweenWorkshops() {
        Workshop mine = authenticatedWorkshop();
        Sale sale = invoice(mine, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_THREE);
        refund(sale, "1000.00", PaymentMethod.CASH);

        Workshop other = authenticatedWorkshop();
        CashClosureResponseDTO theirs = closeOn(DAY_THREE);

        assertThat(theirs.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(theirs.refunds().refunds()).isEmpty();
        assertThat(other).isNotNull();
    }

    // ── Cobro y devolucion el MISMO dia ──────────────────────────
    //
    // El caso que antes se prohibia. Un cobro no se borra porque la venta se
    // revierta despues: el dinero entro a las 10:00 y, si se devolvio, salio a
    // las 15:00. El dia tiene que decir las dos cosas, no ninguna.

    /**
     * Devolucion TOTAL el mismo dia: entraron Q1,000, salieron Q1,000, neto Q0.
     *
     * Lo que NO puede pasar es que el dia diga "Q0 cobrado / Q0 devuelto"
     * —como si nada hubiera ocurrido— ni "Q0 cobrado / Q1,000 devuelto", que
     * cerraria en negativo sin faltar un quetzal en el cajon.
     */
    @Test
    void aFullSameDayRefundKeepsTheCollectionAndTheRefundVisible() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        refund(sale, "1000.00", PaymentMethod.CASH);

        CashClosureResponseDTO today = closeOn(DAY_ONE);

        assertThat(today.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(cashOf(today)).isEqualByComparingTo("1000.00");
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("1000.00");
        assertThat(refundOf(today, PaymentMethod.CASH)).isEqualByComparingTo("1000.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("0.00");
    }

    /** Devolucion PARCIAL el mismo dia: Q1,000 dentro, Q400 fuera, quedan Q600. */
    @Test
    void aPartialSameDayRefundLeavesTheDifferenceAsNet() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        refund(sale, "400.00", PaymentMethod.CASH);

        CashClosureResponseDTO today = closeOn(DAY_ONE);

        assertThat(today.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("600.00");
    }

    /**
     * Reversion el mismo dia SIN devolucion: el dinero entro y no ha salido.
     *
     * La venta deja de contar como facturada —eso no cambia— pero el ingreso
     * sigue ahi, porque nadie devolvio nada.
     */
    @Test
    void aSameDayReversalWithoutRefundStillKeepsTheMoneyIn() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        saleService.reverse(sale.getId(), new ReverseSaleRequestDTO("Sin devolucion"));

        CashClosureResponseDTO today = closeOn(DAY_ONE);

        assertThat(today.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("1000.00");
    }

    /**
     * El canal de entrada y el de salida se cuadran por separado.
     *
     * Se cobro con TARJETA y se devolvio por TRANSFERENCIA. Cada uno aparece en
     * su desglose y en el suyo solamente: imputar la devolucion a la tarjeta
     * habria cuadrado el total y falseado los dos canales.
     */
    @Test
    void theCollectionAndTheRefundReconcileOnTheirOwnChannels() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CARD, "1000.00");
        refund(sale, "400.00", PaymentMethod.TRANSFER);

        CashClosureResponseDTO today = closeOn(DAY_ONE);

        assertThat(cardOf(today.collections())).isEqualByComparingTo("1000.00");
        assertThat(cashOf(today)).isEqualByComparingTo("0.00");
        assertThat(refundOf(today, PaymentMethod.TRANSFER))
                .isEqualByComparingTo("400.00");
        assertThat(refundOf(today, PaymentMethod.CARD)).isEqualByComparingTo("0.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("600.00");
    }

    /**
     * La distincion que este arreglo NO toca: lo FACTURADO sigue sus propias
     * reglas.
     *
     * Una venta revertida deja de estar facturada —desaparece de los articulos
     * y de la facturacion emitida— y a la vez conserva su cobro. No es una
     * incoherencia: son dos preguntas distintas. "Que vendi hoy" mira el estado
     * de la venta; "cuanto dinero movi hoy" mira los pagos que de verdad
     * ocurrieron.
     */
    @Test
    void aSameDayReversalLeavesBillingButKeepsTheCashMovement() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        refund(sale, "1000.00", PaymentMethod.CASH);

        CashClosureResponseDTO today = closeOn(DAY_ONE);

        assertThat(today.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(today.items()).isEmpty();
        assertThat(today.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(today.refunds().totalRefunded()).isEqualByComparingTo("1000.00");
    }

    /** Una venta anulada nunca inventa un cobro: nunca estuvo pagada. */
    @Test
    void aCancelledSaleNeverProducesACollection() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale draft = saleRepository.save(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.CANCELLED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(DAY_ONE.atTime(9, 0))
                .total(new BigDecimal("500.00"))
                .build());

        CashClosureResponseDTO today = closeOn(DAY_ONE);

        assertThat(today.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(today.netPeriod()).isEqualByComparingTo("0.00");
        assertThat(draft.getId()).isNotNull();
    }

    /**
     * Y el snapshot congela las cifras corregidas: lo que la previsualizacion
     * mostraba es lo que el documento confirmado dice.
     */
    @Test
    void theConfirmedSnapshotKeepsTheSameDayCollectionAndRefund() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        refund(sale, "400.00", PaymentMethod.CASH);

        CashClosureResponseDTO preview = service.getPreview();
        assertThat(preview.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(preview.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(preview.netPeriod()).isEqualByComparingTo("600.00");

        closeOn(DAY_ONE);
        CashClosureResponseDTO snapshot = service.getByDate(DAY_ONE);

        assertThat(snapshot.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(snapshot.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(snapshot.netPeriod()).isEqualByComparingTo("600.00");
    }

    /** Y el mes hereda las cifras corregidas sin ningun apaño propio. */
    @Test
    void theMonthInheritsTheCorrectedSameDayFigures() {
        Workshop workshop = authenticatedWorkshop();
        clock.moveTo(DAY_ONE);

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        refund(sale, "400.00", PaymentMethod.CASH);
        closeOn(DAY_ONE);

        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(month.refunds().totalRefunded()).isEqualByComparingTo("400.00");
        assertThat(month.netPeriod()).isEqualByComparingTo("600.00");
    }

    // ── Devoluciones en el cierre mensual ────────────────────────

    /** El mes agrega las devoluciones de sus dias cerrados y da su neto. */
    @Test
    void theMonthlyClosureAggregatesRefundsAndNet() {
        Workshop workshop = authenticatedWorkshop();

        Sale one = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        Sale two = invoice(workshop, DAY_ONE, PaymentMethod.CARD, "500.00");
        closeOn(DAY_ONE);

        clock.moveTo(DAY_TWO);
        invoice(workshop, DAY_TWO, PaymentMethod.CASH, "2000.00");
        refund(one, "1000.00", PaymentMethod.CASH);
        closeOn(DAY_TWO);

        clock.moveTo(DAY_THREE);
        refund(two, "500.00", PaymentMethod.CARD);
        closeOn(DAY_THREE);

        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.collections().totalCollected()).isEqualByComparingTo("3500.00");
        assertThat(month.refunds().totalRefunded()).isEqualByComparingTo("1500.00");
        assertThat(month.refunds().refundCount()).isEqualTo(2L);
        assertThat(month.netPeriod()).isEqualByComparingTo("2000.00");

        assertThat(monthRefundOf(month, PaymentMethod.CASH))
                .isEqualByComparingTo("1000.00");
        assertThat(monthRefundOf(month, PaymentMethod.CARD))
                .isEqualByComparingTo("500.00");
        assertThat(monthRefundOf(month, PaymentMethod.TRANSFER))
                .isEqualByComparingTo("0.00");

        // Y el detalle del mes lista las dos, no solo sus totales.
        assertThat(month.refunds().refunds()).hasSize(2);
    }

    /**
     * El caso a caballo de dos meses, que es la razon de que la devolucion
     * tenga fecha propia.
     *
     * Agosto cobro Q1,000 y septiembre los devolvio. Agosto sigue diciendo
     * Q1,000 cobrados y CERO devueltos; septiembre dice cero cobrados y
     * Q1,000 devueltos, y cierra en negativo. Reescribir agosto habria sido
     * mas simple y habria falseado un mes ya cerrado.
     */
    @Test
    void aRefundBelongsToTheMonthTheMoneyLeftAndNotToTheSaleMonth() {
        Workshop workshop = authenticatedWorkshop();

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        clock.moveTo(NEXT_MONTH_DAY);
        refund(sale, "1000.00", PaymentMethod.CASH);
        closeOn(NEXT_MONTH_DAY);

        CashClosureMonthlyResponseDTO august = service.getMonthly(2026, 8);
        assertThat(august.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(august.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(august.refunds().refunds()).isEmpty();
        assertThat(august.netPeriod()).isEqualByComparingTo("1000.00");

        CashClosureMonthlyResponseDTO september = service.getMonthly(2026, 9);
        assertThat(september.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(september.refunds().totalRefunded()).isEqualByComparingTo("1000.00");
        assertThat(september.netPeriod()).isEqualByComparingTo("-1000.00");
        assertThat(september.refunds().refunds()).singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("1000.00"));
    }

    /** El mes de un taller nunca incluye las devoluciones de otro. */
    @Test
    void theMonthlyRefundsOfOneWorkshopNeverIncludeAnother() {
        Workshop mine = authenticatedWorkshop();
        Sale sale = invoice(mine, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);
        clock.moveTo(DAY_TWO);
        refund(sale, "1000.00", PaymentMethod.CASH);
        closeOn(DAY_TWO);

        authenticatedWorkshop();
        CashClosureMonthlyResponseDTO theirs = service.getMonthly(2026, 8);

        assertThat(theirs.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(theirs.refunds().refunds()).isEmpty();
    }

    /**
     * El mes se lee de los SNAPSHOTS, no de las devoluciones vivas.
     *
     * Una devolucion registrada hoy no puede aparecer en un mes cuyos dias ya
     * estaban cerrados: si el mensual releyera `sale_refund`, el pasado
     * cambiaria cada vez que alguien devuelve algo.
     */
    @Test
    void theMonthlyRefundsComeFromTheConfirmedSnapshots() {
        Workshop workshop = authenticatedWorkshop();

        Sale sale = invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        // Se devuelve el dia DOS pero ese dia NO se cierra.
        clock.moveTo(DAY_TWO);
        refund(sale, "1000.00", PaymentMethod.CASH);

        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        // El mes solo cuenta el dia uno, que es el unico confirmado: la
        // devolucion del dia dos todavia no pertenece a ningun cierre.
        assertThat(month.closedDays()).isEqualTo(1L);
        assertThat(month.refunds().totalRefunded()).isEqualByComparingTo("0.00");
        assertThat(month.status()).isEqualTo(CashClosureMonthStatus.PARTIAL);
    }

    /** Un cierre confirmado no se puede repetir. */
    @Test
    void theSameWorkshopCannotCloseTheSameDayTwice() {
        Workshop workshop = authenticatedWorkshop();
        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "100.00");
        closeOn(DAY_ONE);

        assertThatThrownBy(() -> service.close())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cerrada");
    }

    /** Dos talleres cierran el mismo dia sin estorbarse. */
    @Test
    void twoWorkshopsCanCloseTheSameDateIndependently() {
        Workshop first = authenticatedWorkshop();
        invoice(first, DAY_ONE, PaymentMethod.CASH, "100.00");
        closeOn(DAY_ONE);

        Workshop second = authenticatedWorkshop();
        invoice(second, DAY_ONE, PaymentMethod.CARD, "900.00");
        CashClosureResponseDTO other = closeOn(DAY_ONE);

        assertThat(other.collections().totalCollected()).isEqualByComparingTo("900.00");
        // Y cada uno ve solo lo suyo.
        authenticateAgainst(first);
        assertThat(service.getByDate(DAY_ONE).collections().totalCollected())
                .isEqualByComparingTo("100.00");
    }

    // ── Cierre mensual ───────────────────────────────────────────

    /**
     * El mes agrega los cierres diarios CONFIRMADOS, con la misma estructura
     * contable que un dia.
     */
    @Test
    void theMonthlyClosureAggregatesTheConfirmedDays() {
        Workshop workshop = authenticatedWorkshop();

        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "1000.00");
        closeOn(DAY_ONE);

        invoice(workshop, DAY_TWO, PaymentMethod.CARD, "2000.00");
        closeOn(DAY_TWO);

        clock.moveTo(DAY_THREE);
        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.year()).isEqualTo(2026);
        assertThat(month.month()).isEqualTo(8);
        assertThat(month.closedDays()).isEqualTo(2L);
        assertThat(month.invoicing().totalInvoiced()).isEqualByComparingTo("3000.00");
        assertThat(month.collections().totalCollected()).isEqualByComparingTo("3000.00");
        assertThat(cashOf(month.collections())).isEqualByComparingTo("1000.00");
        assertThat(cardOf(month.collections())).isEqualByComparingTo("2000.00");
    }

    /** Los articulos del mes se reagregan por producto sobre el detalle diario. */
    @Test
    void theMonthlyClosureReaggregatesItemsAcrossDays() {
        Workshop workshop = authenticatedWorkshop();
        Product resorte = product("RESORTE DELANTERO");

        line(invoice(workshop, DAY_ONE, PaymentMethod.CASH, "300.00"), resorte, 2, "300.00");
        closeOn(DAY_ONE);
        line(invoice(workshop, DAY_TWO, PaymentMethod.CASH, "450.00"), resorte, 3, "450.00");
        closeOn(DAY_TWO);

        clock.moveTo(DAY_THREE);
        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        // Dos filas de detalle diario vuelven como UNA del mes.
        assertThat(month.items()).hasSize(1);
        assertThat(month.items().get(0).productId()).isEqualTo(resorte.getId());
        assertThat(month.items().get(0).quantity()).isEqualTo(5L);
        assertThat(month.items().get(0).billedAmount()).isEqualByComparingTo("750.00");
        assertThat(month.itemsBilledAmount()).isEqualByComparingTo("750.00");
    }

    /**
     * Cobrar en el mes una cambiaria emitida el mes ANTERIOR cuenta en los
     * cobros del mes y no en sus emisiones.
     */
    @Test
    void aPriorMonthExchangeCollectedThisMonthCountsInThisMonthsCollections() {
        Workshop workshop = authenticatedWorkshop();
        LocalDate lastMonth = DAY_ONE.minusMonths(1);

        Sale exchange = pendingExchangeInvoice(workshop, lastMonth, "4000.00");

        clock.moveTo(DAY_ONE);
        collect(exchange, DAY_ONE, PaymentMethod.TRANSFER);
        closeOn(DAY_ONE);

        clock.moveTo(DAY_TWO);
        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.collections().totalCollected()).isEqualByComparingTo("4000.00");
        assertThat(month.exchange().collectedAmount()).isEqualByComparingTo("4000.00");
        assertThat(month.exchange().previousCollectedAmount())
                .isEqualByComparingTo("4000.00");
        // No se emitio nada en agosto.
        assertThat(month.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
    }

    // ── Completitud del mes ──────────────────────────────────────

    /** Con dias sin cerrar, el mes es PARCIAL y lo declara. */
    @Test
    void aMonthWithMissingClosuresIsPartial() {
        Workshop workshop = authenticatedWorkshop();
        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "100.00");
        closeOn(DAY_ONE);

        // Estamos a dia 3 y solo se cerro el 1: faltan el 2 y el 3.
        clock.moveTo(DAY_THREE);
        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.status()).isEqualTo(CashClosureMonthStatus.PARTIAL);
        assertThat(month.closedDays()).isEqualTo(1L);
        assertThat(month.pendingDays()).isEqualTo(2L);
    }

    /**
     * Con todos los dias transcurridos cerrados, el mes es COMPLETO. El limite
     * es el ultimo dia que YA OCURRIO: contar como pendientes los dias que aun
     * no han llegado presentaria como incompleto un mes que va al dia.
     */
    @Test
    void aMonthWithEveryElapsedDayClosedIsComplete() {
        Workshop workshop = authenticatedWorkshop();

        invoice(workshop, DAY_ONE, PaymentMethod.CASH, "100.00");
        closeOn(DAY_ONE);
        invoice(workshop, DAY_TWO, PaymentMethod.CASH, "200.00");
        closeOn(DAY_TWO);
        invoice(workshop, DAY_THREE, PaymentMethod.CASH, "300.00");
        closeOn(DAY_THREE);

        // Seguimos en el dia 3: los tres dias transcurridos estan cerrados.
        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.status()).isEqualTo(CashClosureMonthStatus.COMPLETE);
        assertThat(month.closedDays()).isEqualTo(3L);
        assertThat(month.pendingDays()).isZero();
        assertThat(month.to()).isEqualTo(DAY_THREE);
    }

    /** El mes de otro taller nunca se cuela. */
    @Test
    void theMonthlyClosureOfOneWorkshopNeverIncludesAnother() {
        Workshop mine = authenticatedWorkshop();
        invoice(mine, DAY_ONE, PaymentMethod.CASH, "100.00");
        closeOn(DAY_ONE);

        Workshop other = authenticatedWorkshop();
        invoice(other, DAY_ONE, PaymentMethod.CASH, "9999.00");
        closeOn(DAY_ONE);

        authenticateAgainst(mine);
        clock.moveTo(DAY_TWO);
        CashClosureMonthlyResponseDTO month = service.getMonthly(2026, 8);

        assertThat(month.collections().totalCollected()).isEqualByComparingTo("100.00");
    }

    // ── Apoyo del mes ────────────────────────────────────────────

    private static BigDecimal cashOf(
            com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO collections) {
        return methodAmount(collections, PaymentMethod.CASH);
    }

    private static BigDecimal cardOf(
            com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO collections) {
        return methodAmount(collections, PaymentMethod.CARD);
    }

    private static BigDecimal transferOf(CashClosureResponseDTO closure) {
        return methodAmount(closure.collections(), PaymentMethod.TRANSFER);
    }

    private static BigDecimal methodAmount(
            com.cns.spring_house.cashclosure.dto.CashClosureCollectionsDTO collections,
            PaymentMethod method) {
        return collections.methods().stream()
                .filter(row -> row.method() == method)
                .findFirst()
                .map(ClosurePaymentMethodDTO::amount)
                .orElseThrow();
    }

    private int catalogSequence;

    private Product product(String name) {
        catalogSequence++;
        Family family = familyRepository.save(Family.builder()
                .code("CC" + catalogSequence + unique())
                .name("FAM " + catalogSequence + " " + unique())
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        return productRepository.save(Product.builder()
                .family(family)
                .name(name)
                .attributes(new HashMap<>())
                .build());
    }

    private void line(Sale sale, Product product, int quantity, String subTotal) {
        saleDetailRepository.save(SaleDetail.builder()
                .sale(sale)
                .product(product)
                .quantity(quantity)
                .price(new BigDecimal(subTotal).divide(BigDecimal.valueOf(quantity)))
                .subTotal(new BigDecimal(subTotal))
                .build());
    }

}
