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

import java.util.HashMap;
import java.util.List;

import com.cns.spring_house.cashclosure.dto.ClosureExchangeInvoiceDTO;
import com.cns.spring_house.cashclosure.dto.ClosurePaymentMethodDTO;
import com.cns.spring_house.cashclosure.dto.ClosureSoldItemDTO;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.refund.repository.SaleRefundRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * La semantica del cierre de caja, contra PostgreSQL real.
 *
 * Se prueba aqui y no con mocks porque lo que se comprueba ES el SQL: que la
 * facturacion se cuente por `completed_at`, los cobros por `paid_at`, que los
 * articulos se agrupen por identidad estable y que el taller acote todo dentro
 * de la propia sentencia. Un mock del repositorio devolveria lo que se le
 * pidiera y no probaria ninguna de esas cosas.
 *
 * La regla que gobierna todas las comprobaciones:
 *
 * <pre>
 * FACTURADO -> lo emitido, por completed_at
 * COBRADO   -> el dinero que entro, por paid_at
 * </pre>
 *
 * Una cambiaria emitida y no cobrada suma en lo primero y NO en lo segundo. Ese
 * es el error contable que este cierre existe para no cometer.
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

    @Autowired
    private SaleRefundRepository refundRepository;

    private CashClosureCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CashClosureCalculator(saleRepository, refundRepository);
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

    private int catalogSequence;

    /** Producto de catalogo con la categoria indicada. */
    private Product product(String name, FamilyCategory category) {
        catalogSequence++;
        Family family = entityManager.persistAndFlush(Family.builder()
                .code("F" + catalogSequence)
                .name(category.name() + " " + catalogSequence)
                .requiresModel(false)
                .category(category)
                .build());
        return entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name(name)
                .attributes(new HashMap<>())
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
    private Sale pendingExchangeInvoice(
            Workshop workshop, LocalDate issuedOn, String total) {
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

    /** Una linea de venta. El subtotal es el importe HISTORICO de la linea. */
    private SaleDetail line(Sale sale, Product product, int quantity, String subTotal) {
        return entityManager.persistAndFlush(SaleDetail.builder()
                .sale(sale)
                .product(product)
                .quantity(quantity)
                .price(new BigDecimal(subTotal)
                        .divide(BigDecimal.valueOf(quantity)))
                .subTotal(new BigDecimal(subTotal))
                .build());
    }

    private PeriodCashFigures figuresOf(Workshop workshop) {
        return calculator.calculate(workshop.getId(), TODAY);
    }

    private static BigDecimal amountOf(
            List<ClosurePaymentMethodDTO> methods, PaymentMethod method) {
        return methods.stream()
                .filter(row -> row.method() == method)
                .findFirst()
                .orElseThrow()
                .amount();
    }

    // ═══ El ejemplo contable de referencia ════════════════════════

    /**
     * El caso que define el cierre entero:
     *
     * <pre>
     * Facturas normales emitidas hoy y cobradas hoy:  Q5,000
     * Cambiarias emitidas hoy, aun sin cobrar:        Q2,000
     * Cambiarias cobradas hoy, emitidas antes:        Q1,000
     * </pre>
     *
     * El total cobrado es Q6,000 y NO Q7,000: los Q2,000 emitidos a credito son
     * facturacion, no dinero. Cuando se cobren, sumaran al dia en que se cobren.
     */
    @Test
    void issuedCreditIsNeverCountedAsCollectedMoney() {
        Workshop workshop = workshop("TALLER A");

        invoice(workshop, TODAY, PaymentMethod.CASH, "5000.00");
        pendingExchangeInvoice(workshop, TODAY, "2000.00");
        collectedExchangeInvoice(
                workshop, YESTERDAY, TODAY, PaymentMethod.CASH, "1000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().invoiceTotal()).isEqualByComparingTo("5000.00");
        assertThat(figures.exchange().issuedAmount()).isEqualByComparingTo("2000.00");
        assertThat(figures.exchange().collectedAmount()).isEqualByComparingTo("1000.00");

        // Q5,000 + Q1,000. Los Q2,000 pendientes no entran.
        assertThat(figures.collections().totalCollected())
                .isEqualByComparingTo("6000.00");
        assertThat(figures.collections().totalCollected())
                .isNotEqualByComparingTo("7000.00");
    }

    /**
     * El desglose por forma de pago SUMA exactamente el total cobrado. Si no
     * cuadrara, alguna via de cobro se estaria perdiendo por el camino.
     */
    @Test
    void paymentMethodsReconcileToTheCollectedTotal() {
        Workshop workshop = workshop("TALLER A");

        invoice(workshop, TODAY, PaymentMethod.CASH, "1000.00");
        invoice(workshop, TODAY, PaymentMethod.CARD, "2000.00");
        // Una cambiaria antigua cobrada hoy entra en el desglose como cualquier
        // otro cobro: lo que cuenta es cuando entro el dinero, no cuando se
        // emitio el documento.
        collectedExchangeInvoice(
                workshop, YESTERDAY, TODAY, PaymentMethod.TRANSFER, "3000.00");

        PeriodCashFigures figures = figuresOf(workshop);
        List<ClosurePaymentMethodDTO> methods = figures.collections().methods();

        assertThat(amountOf(methods, PaymentMethod.CASH)).isEqualByComparingTo("1000.00");
        assertThat(amountOf(methods, PaymentMethod.CARD)).isEqualByComparingTo("2000.00");
        assertThat(amountOf(methods, PaymentMethod.TRANSFER))
                .isEqualByComparingTo("3000.00");

        BigDecimal sum = methods.stream()
                .map(ClosurePaymentMethodDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(figures.collections().totalCollected());
        assertThat(sum).isEqualByComparingTo("6000.00");
    }

    /** Se devuelve una fila por metodo aunque no se haya cobrado nada por el. */
    @Test
    void everyPaymentMethodGetsARowEvenAtZero() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "500.00");

        List<ClosurePaymentMethodDTO> methods = figuresOf(workshop).collections().methods();

        assertThat(methods).hasSize(PaymentMethod.values().length);
        assertThat(amountOf(methods, PaymentMethod.CARD)).isEqualByComparingTo("0.00");
        assertThat(amountOf(methods, PaymentMethod.TRANSFER)).isEqualByComparingTo("0.00");
    }

    // ═══ Facturacion emitida ═════════════════════════════════════

    @Test
    void countsInvoicesAndExchangeInvoicesIssuedTodaySeparately() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "1000.00");
        invoice(workshop, TODAY, PaymentMethod.CARD, "2000.00");
        pendingExchangeInvoice(workshop, TODAY, "8000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().invoiceCount()).isEqualTo(2);
        assertThat(figures.invoicing().invoiceTotal()).isEqualByComparingTo("3000.00");
        assertThat(figures.invoicing().exchangeInvoiceCount()).isEqualTo(1);
        assertThat(figures.invoicing().exchangeInvoiceTotal())
                .isEqualByComparingTo("8000.00");
        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("11000.00");
    }

    /** Lo emitido AYER no es facturacion de hoy, aunque se cobre hoy. */
    @Test
    void yesterdaysInvoicingDoesNotCountAsTodays() {
        Workshop workshop = workshop("TALLER A");
        collectedExchangeInvoice(
                workshop, YESTERDAY, TODAY, PaymentMethod.CASH, "1000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("1000.00");
    }

    // ═══ Cambiarias: dos conjuntos distintos ═════════════════════

    /**
     * Emitida hoy y pendiente: sale en EMITIDAS con estado pendiente, y no
     * aparece en COBRADAS.
     */
    @Test
    void exchangeIssuedTodayAndStillPending() {
        Workshop workshop = workshop("TALLER A");
        pendingExchangeInvoice(workshop, TODAY, "2000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.exchange().issued()).hasSize(1);
        assertThat(figures.exchange().issued().get(0).collected()).isFalse();
        assertThat(figures.exchange().issued().get(0).paidAt()).isNull();
        assertThat(figures.exchange().issued().get(0).paymentMethod()).isNull();
        assertThat(figures.exchange().collected()).isEmpty();
        assertThat(figures.exchange().issuedPendingAmount())
                .isEqualByComparingTo("2000.00");
    }

    /**
     * Emitida hoy y cobrada hoy: sale en LAS DOS listas, y eso es correcto —son
     * dos hechos distintos sobre el mismo documento—. Su dinero entra UNA vez,
     * porque el total sale del desglose por forma de pago.
     */
    @Test
    void exchangeIssuedAndCollectedTodayIsNotDoubleCounted() {
        Workshop workshop = workshop("TALLER A");
        collectedExchangeInvoice(workshop, TODAY, TODAY, PaymentMethod.CARD, "1500.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.exchange().issued()).hasSize(1);
        assertThat(figures.exchange().collected()).hasSize(1);
        assertThat(figures.exchange().issued().get(0).saleId())
                .isEqualTo(figures.exchange().collected().get(0).saleId());

        // Emitido Q1,500 y cobrado Q1,500... pero el total cobrado es Q1,500,
        // no Q3,000.
        assertThat(figures.exchange().issuedAmount()).isEqualByComparingTo("1500.00");
        assertThat(figures.exchange().collectedAmount()).isEqualByComparingTo("1500.00");
        assertThat(figures.collections().totalCollected())
                .isEqualByComparingTo("1500.00");
        // Y nada queda pendiente de lo emitido hoy.
        assertThat(figures.exchange().issuedPendingAmount()).isEqualByComparingTo("0.00");
    }

    /**
     * Emitida AYER y cobrada hoy: pertenece a los cobros de hoy y NO a las
     * emisiones de hoy. Es el caso que obliga a no exigir que ambas fechas
     * caigan en la misma ventana.
     */
    @Test
    void exchangeIssuedYesterdayAndCollectedTodayBelongsToTodaysCollections() {
        Workshop workshop = workshop("TALLER A");
        collectedExchangeInvoice(
                workshop, YESTERDAY, TODAY, PaymentMethod.TRANSFER, "1000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.exchange().issued()).isEmpty();
        assertThat(figures.exchange().collected()).hasSize(1);
        assertThat(figures.exchange().collectedAmount()).isEqualByComparingTo("1000.00");
        // Y se declara cuanto de lo cobrado venia de antes.
        assertThat(figures.exchange().previousCollectedAmount())
                .isEqualByComparingTo("1000.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("1000.00");
    }

    /** Una cambiaria del mes pasado cobrada hoy cuenta igual. */
    @Test
    void exchangeIssuedLastMonthAndCollectedTodayCounts() {
        Workshop workshop = workshop("TALLER A");
        collectedExchangeInvoice(
                workshop, TODAY.minusMonths(1), TODAY, PaymentMethod.CASH, "4000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.exchange().collectedAmount()).isEqualByComparingTo("4000.00");
        assertThat(figures.exchange().previousCollectedAmount())
                .isEqualByComparingTo("4000.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("4000.00");
    }

    /** El documento se identifica por un codigo corto, no por el UUID crudo. */
    @Test
    void exchangeInvoicesCarryAReadableDocumentCode() {
        Workshop workshop = workshop("TALLER A");
        Sale exchange = pendingExchangeInvoice(workshop, TODAY, "2000.00");

        ClosureExchangeInvoiceDTO invoice = figuresOf(workshop).exchange().issued().get(0);

        assertThat(invoice.documentCode()).hasSize(8);
        assertThat(invoice.documentCode())
                .isEqualTo(exchange.getId().toString().substring(0, 8).toUpperCase());
        assertThat(invoice.documentCode()).doesNotContain("-");
        // El UUID sigue disponible para abrir el documento, pero aparte.
        assertThat(invoice.saleId()).isEqualTo(exchange.getId());
    }

    // ═══ Articulos vendidos ══════════════════════════════════════

    /** El mismo producto en varias ventas se agrega en una sola fila. */
    @Test
    void aggregatesTheSameProductAcrossSales() {
        Workshop workshop = workshop("TALLER A");
        Product resorte = product("RESORTE DELANTERO", FamilyCategory.NORMAL);

        line(invoice(workshop, TODAY, PaymentMethod.CASH, "300.00"), resorte, 2, "300.00");
        line(invoice(workshop, TODAY, PaymentMethod.CARD, "450.00"), resorte, 3, "450.00");

        List<ClosureSoldItemDTO> items = figuresOf(workshop).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).quantity()).isEqualTo(5);
        assertThat(items.get(0).billedAmount()).isEqualByComparingTo("750.00");
    }

    /**
     * Dos productos DISTINTOS que se llaman igual no se fusionan. El catalogo
     * real los tiene, y agrupar por texto los presentaria como uno solo.
     */
    @Test
    void doesNotMergeDistinctProductsWithTheSameName() {
        Workshop workshop = workshop("TALLER A");
        Product first = product("HOJA DE RESORTE 1200", FamilyCategory.NORMAL);
        Product second = product("HOJA DE RESORTE 1200", FamilyCategory.NORMAL);

        line(invoice(workshop, TODAY, PaymentMethod.CASH, "100.00"), first, 1, "100.00");
        line(invoice(workshop, TODAY, PaymentMethod.CASH, "200.00"), second, 1, "200.00");

        List<ClosureSoldItemDTO> items = figuresOf(workshop).items();

        assertThat(items).hasSize(2);
        assertThat(items).extracting(ClosureSoldItemDTO::productId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    /** El subtotal historico manda: dos precios distintos suman lo que costaron. */
    @Test
    void sumsHistoricalSubtotalsRatherThanCatalogPrice() {
        Workshop workshop = workshop("TALLER A");
        Product resorte = product("RESORTE", FamilyCategory.NORMAL);

        line(invoice(workshop, TODAY, PaymentMethod.CASH, "100.00"), resorte, 1, "100.00");
        // La misma pieza, vendida mas cara en otra venta del mismo dia.
        line(invoice(workshop, TODAY, PaymentMethod.CASH, "150.00"), resorte, 1, "150.00");

        assertThat(figuresOf(workshop).items().get(0).billedAmount())
                .isEqualByComparingTo("250.00");
    }

    /**
     * Un servicio factura igual que la mercaderia y NO puede desaparecer de la
     * tabla: la pregunta es que se facturo. Su categoria viaja para poder
     * distinguirlo.
     */
    @Test
    void includesServicesAlongsideGoods() {
        Workshop workshop = workshop("TALLER A");
        Product resorte = product("RESORTE", FamilyCategory.NORMAL);
        Product instalacion = product("INSTALACION", FamilyCategory.JOB);

        Sale sale = invoice(workshop, TODAY, PaymentMethod.CASH, "700.00");
        line(sale, resorte, 1, "400.00");
        line(sale, instalacion, 1, "300.00");

        List<ClosureSoldItemDTO> items = figuresOf(workshop).items();

        assertThat(items).hasSize(2);
        assertThat(items).extracting(ClosureSoldItemDTO::type)
                .containsExactlyInAnyOrder(FamilyCategory.NORMAL, FamilyCategory.JOB);
        assertThat(figuresOf(workshop).itemsBilledAmount())
                .isEqualByComparingTo("700.00");
    }

    /**
     * Una cambiaria SIN COBRAR aporta sus articulos a la actividad facturada
     * pero ni un quetzal al total cobrado. Es la separacion entera del cierre en
     * una sola prueba.
     */
    @Test
    void unpaidExchangeContributesItemsButNoCollectedMoney() {
        Workshop workshop = workshop("TALLER A");
        Product resorte = product("RESORTE", FamilyCategory.NORMAL);

        Sale exchange = pendingExchangeInvoice(workshop, TODAY, "2000.00");
        line(exchange, resorte, 4, "2000.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.items()).hasSize(1);
        assertThat(figures.items().get(0).quantity()).isEqualTo(4);
        assertThat(figures.itemsBilledAmount()).isEqualByComparingTo("2000.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("0.00");
    }

    // ═══ Universo de ventas y aislamiento ════════════════════════

    /** PENDING, CANCELLED y REVERSED no cuadran ninguna caja. */
    @Test
    void excludesSalesThatNeverBecameRevenue() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "1000.00");

        // Nunca estuvo pagada: solo se cancela lo que sigue abierto.
        sale(workshop, SaleStatus.CANCELLED, InvoiceType.INVOICE, PaymentStatus.PENDING,
                null, TODAY.atTime(9, 0), null, "9999.00");
        // Ni siquiera se completo.
        sale(workshop, SaleStatus.PENDING, null, PaymentStatus.PENDING,
                null, null, null, "7777.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("1000.00");
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("1000.00");
    }

    /**
     * Una venta REVERTIDA que se habia cobrado SI aporta su cobro.
     *
     * Es la correccion que separa las dos preguntas del cierre. El dinero entro
     * cuando entro: revertir la venta despues no lo devuelve al cliente por si
     * solo —para eso existe la devolucion, que es un hecho aparte y con su
     * propia fecha—. Antes esta venta desaparecia del cobro, y si ademas se
     * habia devuelto el dinero, la caja restaba una salida cuyo ingreso ya no
     * estaba: el dia cerraba en negativo sin faltar un quetzal en el cajon.
     *
     * Lo FACTURADO sigue su propia regla y la venta revertida no cuenta ahi.
     * No es una incoherencia: "que vendi hoy" mira el estado de la venta y
     * "cuanto dinero movi hoy" mira los pagos que de verdad ocurrieron.
     */
    @Test
    void aReversedSaleThatWasPaidStillCountsAsCollected() {
        Workshop workshop = workshop("TALLER A");
        invoice(workshop, TODAY, PaymentMethod.CASH, "1000.00");

        sale(workshop, SaleStatus.REVERSED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "8888.00");

        PeriodCashFigures figures = figuresOf(workshop);

        // El dinero de las dos entro.
        assertThat(figures.collections().totalCollected())
                .isEqualByComparingTo("9888.00");
        // Pero solo una sigue facturada.
        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("1000.00");
    }

    /** El aislamiento por taller es la garantia que no puede fallar. */
    @Test
    void neverMixesAnotherWorkshop() {
        Workshop workshop = workshop("TALLER A");
        Workshop other = workshop("TALLER B");

        Product mine = product("RESORTE", FamilyCategory.NORMAL);
        Product theirs = product("RESORTE AJENO", FamilyCategory.NORMAL);

        line(invoice(workshop, TODAY, PaymentMethod.CASH, "100.00"), mine, 1, "100.00");
        line(invoice(other, TODAY, PaymentMethod.CASH, "9999.00"), theirs, 9, "9999.00");
        pendingExchangeInvoice(other, TODAY, "5555.00");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("100.00");
        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("100.00");
        assertThat(figures.items()).hasSize(1);
        assertThat(figures.items().get(0).productId()).isEqualTo(mine.getId());
        assertThat(figures.exchange().issued()).isEmpty();
    }

    /** Un dia sin actividad responde ceros, no nulos. */
    @Test
    void anEmptyDayReportsZeros() {
        Workshop workshop = workshop("TALLER A");

        PeriodCashFigures figures = figuresOf(workshop);

        assertThat(figures.items()).isEmpty();
        assertThat(figures.itemsBilledAmount()).isEqualByComparingTo("0.00");
        assertThat(figures.invoicing().totalInvoiced()).isEqualByComparingTo("0.00");
        assertThat(figures.exchange().issued()).isEmpty();
        assertThat(figures.exchange().collected()).isEmpty();
        assertThat(figures.collections().totalCollected()).isEqualByComparingTo("0.00");
    }

}
