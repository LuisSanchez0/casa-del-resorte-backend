package com.cns.spring_house.cashclosure.service;

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
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * La semantica del cierre de caja, contra PostgreSQL real.
 *
 * Se prueba aqui y no con mocks porque lo que se comprueba ES el SQL: que la
 * facturacion se cuente por `completed_at`, los cobros por `paid_at`, la cartera
 * pendiente sin limite de fecha, y que el taller acote todo dentro de la propia
 * sentencia. Un mock del repositorio devolveria lo que se le pidiera y no
 * probaria ninguna de esas cuatro cosas.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CashClosureCalculatorQueriesTest {

    private static final String TEMPORARY_DATABASE = "cash_closure_calc_it";

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private TestEntityManager entityManager;

    private CashClosureCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CashClosureCalculator(saleRepository);
    }

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

    private Employee employee(Workshop workshop) {
        dpiSequence++;
        return entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("ANA")
                .lastName("APELLIDO")
                .dpi(String.format("%013d", dpiSequence))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    /** Factura normal: emitida y cobrada en el mismo acto. */
    private Sale invoice(
            Workshop workshop, LocalDate day, PaymentMethod method, String total) {
        return sale(workshop, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, method,
                day.atTime(10, 0), day.atTime(10, 0), total);
    }

    /** Cambiaria emitida y todavia sin cobrar. */
    private Sale pendingExchangeInvoice(Workshop workshop, LocalDate issuedOn, String total) {
        return sale(workshop, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null,
                issuedOn.atTime(11, 0), null, total);
    }

    /** Cambiaria emitida un dia y cobrada otro. */
    private Sale collectedExchangeInvoice(
            Workshop workshop, LocalDate issuedOn, LocalDate collectedOn,
            PaymentMethod method, String total) {
        return sale(workshop, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PAID, method,
                issuedOn.atTime(11, 0), collectedOn.atTime(15, 30), total);
    }

    private Sale sale(
            Workshop workshop, SaleStatus status, InvoiceType invoiceType,
            PaymentStatus paymentStatus, PaymentMethod paymentMethod,
            LocalDateTime completedAt, LocalDateTime paidAt, String total) {
        return entityManager.persistAndFlush(Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .status(status)
                .invoiceType(invoiceType)
                .paymentStatus(paymentStatus)
                .paymentMethod(paymentMethod)
                .completedAt(completedAt)
                .paidAt(paidAt)
                .total(new BigDecimal(total))
                .build());
    }

    private DailyCashFigures figuresOf(Workshop workshop) {
        return calculator.calculate(workshop.getId(), TODAY);
    }

    // ── Facturacion ──────────────────────────────────────────────

    @Test
    void countsInvoicesAndExchangeInvoicesIssuedTodaySeparately() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "1000.00");
        invoice(workshop, TODAY, PaymentMethod.CARD, "2000.00");
        pendingExchangeInvoice(workshop, TODAY, "8000.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().invoiceCount()).isEqualTo(2);
        assertThat(figures.invoicing().invoiceTotal())
                .isEqualByComparingTo("3000.00");
        assertThat(figures.invoicing().exchangeInvoiceCount()).isEqualTo(1);
        assertThat(figures.invoicing().exchangeInvoiceTotal())
                .isEqualByComparingTo("8000.00");
        assertThat(figures.invoicing().totalInvoiced())
                .isEqualByComparingTo("11000.00");
    }

    /**
     * Una cambiaria emitida hoy y sin cobrar es facturacion y es cartera, pero
     * NO es dinero: mezclar las tres cosas es exactamente el error que este
     * modulo existe para evitar.
     */
    @Test
    void aPendingExchangeInvoiceCountsAsInvoicedAndAsReceivableButNeverAsCollected() {
        Workshop workshop = workshop("TALLER A");
        pendingExchangeInvoice(workshop, TODAY, "10000.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("10000.00");
        assertThat(figures.receivables().pendingAmount()).isEqualByComparingTo("10000.00");

        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(figures.collections().cashAmount()).isEqualByComparingTo("0.00");
        assertThat(figures.collections().cardAmount()).isEqualByComparingTo("0.00");
        assertThat(figures.collections().transferAmount()).isEqualByComparingTo("0.00");
        assertThat(figures.receivables().collectedAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * La otra mitad del caso anterior: el dia en que por fin se cobra, el dinero
     * entra sin sumar un quetzal a la facturacion de ESE dia.
     */
    @Test
    void anExchangeInvoiceIssuedEarlierAndCollectedTodayCountsOnlyAsCollected() {
        Workshop workshop = workshop("TALLER A");
        collectedExchangeInvoice(
                workshop, YESTERDAY, TODAY, PaymentMethod.TRANSFER, "10000.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(figures.invoicing().exchangeInvoiceCount()).isZero();

        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("10000.00");
        assertThat(figures.collections().transferCount()).isEqualTo(1);
        assertThat(figures.collections().transferAmount())
                .isEqualByComparingTo("10000.00");

        assertThat(figures.receivables().collectedCount()).isEqualTo(1);
        assertThat(figures.receivables().collectedAmount())
                .isEqualByComparingTo("10000.00");
        assertThat(figures.receivables().previousCollectedAmount())
                .isEqualByComparingTo("10000.00");
        assertThat(figures.receivables().pendingAmount()).isEqualByComparingTo("0.00");
    }

    /** Una cambiaria emitida y cobrada el mismo dia no es cartera anterior. */
    @Test
    void anExchangeInvoiceIssuedAndCollectedTodayIsNotPreviousPortfolio() {
        Workshop workshop = workshop("TALLER A");
        collectedExchangeInvoice(workshop, TODAY, TODAY, PaymentMethod.CASH, "500.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.receivables().collectedAmount()).isEqualByComparingTo("500.00");
        assertThat(figures.receivables().previousCollectedAmount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void aNormalInvoiceCountsAsInvoicedCollectedAndCash() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "1250.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().invoiceTotal()).isEqualByComparingTo("1250.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("1250.00");
        assertThat(figures.collections().cashCount()).isEqualTo(1);
        assertThat(figures.collections().cashAmount()).isEqualByComparingTo("1250.00");
    }

    // ── Formas de pago ───────────────────────────────────────────

    @Test
    void splitsCollectionsByPaymentMethodWithExactCountsAndAmounts() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "4000.00");
        invoice(workshop, TODAY, PaymentMethod.CASH, "3000.00");
        invoice(workshop, TODAY, PaymentMethod.CARD, "6000.00");
        invoice(workshop, TODAY, PaymentMethod.TRANSFER, "5000.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.collections().cashCount()).isEqualTo(2);
        assertThat(figures.collections().cashAmount()).isEqualByComparingTo("7000.00");
        assertThat(figures.collections().cardCount()).isEqualTo(1);
        assertThat(figures.collections().cardAmount()).isEqualByComparingTo("6000.00");
        assertThat(figures.collections().transferCount()).isEqualTo(1);
        assertThat(figures.collections().transferAmount()).isEqualByComparingTo("5000.00");
        assertThat(figures.collections().totalCollected())
                .isEqualByComparingTo("18000.00");
    }

    // ── Cartera pendiente ────────────────────────────────────────

    /**
     * La cartera pendiente NO se limita a lo emitido hoy: es lo que el taller
     * tiene por cobrar en este momento, venga del dia que venga.
     */
    @Test
    void pendingPortfolioIncludesOlderUncollectedExchangeInvoices() {
        Workshop workshop = workshop("TALLER A");
        pendingExchangeInvoice(workshop, TODAY.minusMonths(2), "15000.00");
        pendingExchangeInvoice(workshop, TODAY, "10000.00");
        collectedExchangeInvoice(
                workshop, TODAY.minusDays(5), TODAY, PaymentMethod.CARD, "3000.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.receivables().pendingAmount())
                .isEqualByComparingTo("25000.00");
    }

    // ── Aislamiento por taller ───────────────────────────────────

    @Test
    void theClosureOfOneWorkshopNeverIncludesAnythingFromAnother() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");

        invoice(workshopA, TODAY, PaymentMethod.CASH, "1000.00");
        pendingExchangeInvoice(workshopA, TODAY, "2000.00");

        invoice(workshopB, TODAY, PaymentMethod.CASH, "9999.00");
        invoice(workshopB, TODAY, PaymentMethod.CARD, "8888.00");
        pendingExchangeInvoice(workshopB, TODAY, "7777.00");

        DailyCashFigures figures = figuresOf(workshopA);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("3000.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(figures.collections().cardAmount()).isEqualByComparingTo("0.00");
        assertThat(figures.receivables().pendingAmount()).isEqualByComparingTo("2000.00");
    }

    // ── Estados excluidos ────────────────────────────────────────

    /**
     * Una venta que nunca llego a emitirse, una anulada y una revertida no
     * cuadran ninguna caja: si inflaran los agregados, el arqueo pediria un
     * efectivo que nadie cobro.
     */
    @Test
    void pendingCancelledAndReversedSalesDoNotInflateAnyFigure() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "1000.00");

        sale(workshop, SaleStatus.PENDING, null, PaymentStatus.PENDING, null,
                null, null, "500.00");
        sale(workshop, SaleStatus.CANCELLED, null, PaymentStatus.PENDING, null,
                null, null, "600.00");
        sale(workshop, SaleStatus.REVERSED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "700.00");
        sale(workshop, SaleStatus.REVERSED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null, TODAY.atTime(9, 0), null, "800.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("1000.00");
        assertThat(figures.invoicing().invoiceCount()).isEqualTo(1);
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("1000.00");
        assertThat(figures.receivables().pendingAmount()).isEqualByComparingTo("0.00");
    }

    // ── Limites del dia ──────────────────────────────────────────

    /**
     * La ventana es `[00:00 de hoy, 00:00 de manana)`. Lo de las 23:59:59.999
     * entra y lo del arranque del dia siguiente no: un limite escrito a mano
     * como 23:59:59 dejaria fuera el ultimo segundo del dia.
     */
    @Test
    void theDayIsAHalfOpenIntervalFromMidnightToMidnight() {
        Workshop workshop = workshop("TALLER A");

        sale(workshop, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH,
                TODAY.atStartOfDay(), TODAY.atStartOfDay(), "100.00");
        sale(workshop, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH,
                TODAY.atTime(23, 59, 59, 999_000_000),
                TODAY.atTime(23, 59, 59, 999_000_000), "200.00");
        sale(workshop, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH,
                TODAY.plusDays(1).atStartOfDay(), TODAY.plusDays(1).atStartOfDay(),
                "999.00");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().invoiceCount()).isEqualTo(2);
        assertThat(figures.invoicing().invoiceTotal()).isEqualByComparingTo("300.00");
        assertThat(figures.collections().cashAmount()).isEqualByComparingTo("300.00");
    }

    // ── Arqueo ───────────────────────────────────────────────────

    /**
     * Tarjeta y transferencia NO entran al efectivo esperado: ese dinero fue al
     * banco, no al cajon.
     */
    @Test
    void expectedCashAddsOnlyTheOpeningFundAndTheCashCollected() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "7000.00");
        invoice(workshop, TODAY, PaymentMethod.CARD, "6000.00");
        invoice(workshop, TODAY, PaymentMethod.TRANSFER, "5000.00");

        DailyCashFigures figures = figuresOf(workshop);
        BigDecimal openingCash = new BigDecimal("500.00");

        assertThat(figures.expectedCash(openingCash)).isEqualByComparingTo("7500.00");
        assertThat(figures.difference(openingCash, new BigDecimal("7480.00")))
                .isEqualByComparingTo("-20.00");
    }

    @Test
    void aDayWithoutSalesIsAllZerosAndNotAnError() {
        Workshop workshop = workshop("TALLER A");

        DailyCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("0.00");
        assertThat(figures.receivables().pendingAmount()).isEqualByComparingTo("0.00");
        assertThat(figures.expectedCash(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
    }

    /** El taller que no existe no revienta: simplemente no tiene cifras. */
    @Test
    void anUnknownWorkshopSimplyHasNoFigures() {
        DailyCashFigures figures = calculator.calculate(UUID.randomUUID(), TODAY);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("0.00");
    }

}
