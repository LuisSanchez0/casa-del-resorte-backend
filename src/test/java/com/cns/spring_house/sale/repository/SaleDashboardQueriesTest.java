package com.cns.spring_house.sale.repository;

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
import java.util.HashMap;
import java.util.List;
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

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.projection.PaymentMethodStatProjection;
import com.cns.spring_house.sale.repository.projection.SalesByDayProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.sale.repository.projection.TopProductProjection;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Ejecuta las agregaciones del dashboard de ventas contra PostgreSQL real
 * sobre una base temporal creada por la propia prueba, igual que
 * {@link com.cns.spring_house.vehiclemovement.repository.VehicleMovementDashboardQueriesTest}.
 *
 * Se prueban aqui y no con mocks porque lo que se comprueba ES el SQL: el
 * filtro por estado, el agrupado por dia, el join a producto y el aislamiento
 * por taller/empleado ocurren dentro de la sentencia.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleDashboardQueriesTest {

    private static final String TEMPORARY_DATABASE = "sale_dashboard_it";

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final LocalDateTime DAY_START = TODAY.atStartOfDay();
    private static final LocalDateTime DAY_END = TODAY.plusDays(1).atStartOfDay();

    @Autowired
    private SaleRepository repository;

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

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

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

    private Product product(String name) {
        String code = unique();
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(code)
                .name("FAMILIA " + code)
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        return entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name(name)
                .attributes(new HashMap<>())
                .build());
    }

    private SaleDetail detail(Product product, int quantity, String price, String invoiceDescription) {
        BigDecimal unitPrice = new BigDecimal(price);
        return SaleDetail.builder()
                .product(product)
                .quantity(quantity)
                .price(unitPrice)
                .subTotal(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                .invoiceDescription(invoiceDescription)
                .build();
    }

    private Sale sale(
            Workshop workshop, Employee employee, SaleStatus status,
            InvoiceType invoiceType, PaymentStatus paymentStatus, PaymentMethod paymentMethod,
            LocalDateTime completedAt, LocalDateTime paidAt, String total,
            SaleDetail... details) {

        Sale sale = Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .status(status)
                .invoiceType(invoiceType)
                .paymentStatus(paymentStatus)
                .paymentMethod(paymentMethod)
                .completedAt(completedAt)
                .paidAt(paidAt)
                .total(new BigDecimal(total))
                .build();
        for (SaleDetail detail : details) {
            sale.addDetail(detail);
        }
        sale.setTotal(new BigDecimal(total));
        return entityManager.persistAndFlush(sale);
    }

    // ── Conteos de exito ─────────────────────────────────────────

    /** CANCELLED y REVERSED no son ventas exitosas: ninguna cuenta. */
    @Test
    void onlyCompletedSalesCountAsSuccessful() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        sale(workshop, ana, SaleStatus.PENDING, null, PaymentStatus.PENDING, null,
                null, null, "0.00");
        sale(workshop, ana, SaleStatus.CANCELLED, null, PaymentStatus.PENDING, null,
                null, null, "0.00");
        sale(workshop, ana, SaleStatus.REVERSED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(8, 0), TODAY.atTime(8, 0), "50.00");

        assertThat(repository.countByWorkshopIdAndStatus(workshop.getId(), SaleStatus.COMPLETED))
                .isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndStatus(workshop.getId(), SaleStatus.PENDING))
                .isEqualTo(1L);
    }

    @Test
    void pendingExchangeInvoicesRequireCompletedStatusAndPendingPayment() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null, TODAY.atTime(9, 0), null, "300.00");
        // Ya cobrada: no cuenta como pendiente.
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PAID, PaymentMethod.TRANSFER, TODAY.atTime(9, 0),
                TODAY.atTime(10, 0), "150.00");
        // Factura normal: no es cambiaria.
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH, TODAY.atTime(9, 0),
                TODAY.atTime(9, 0), "80.00");

        long pending = repository.countByWorkshopIdAndStatusAndInvoiceTypeAndPaymentStatus(
                workshop.getId(), SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING);

        assertThat(pending).isEqualTo(1L);
    }

    @Test
    void everyCountIsIsolatedByWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Employee ana = employee(workshopA, "ANA");
        Employee beto = employee(workshopB, "BETO");
        sale(workshopA, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        sale(workshopB, beto, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "999.00");

        assertThat(repository.countByWorkshopIdAndStatus(workshopA.getId(), SaleStatus.COMPLETED))
                .isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndStatus(workshopB.getId(), SaleStatus.COMPLETED))
                .isEqualTo(1L);
    }

    // ── Ambito personal ──────────────────────────────────────────

    @Test
    void personalScopeCountsOnlyTheEmployeesOwnSales() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        Employee luis = employee(workshop, "LUIS");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        sale(workshop, luis, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "200.00");

        assertThat(repository.countByWorkshopIdAndEmployeeIdAndStatus(
                workshop.getId(), ana.getId(), SaleStatus.COMPLETED)).isEqualTo(1L);
        assertThat(repository.countByWorkshopIdAndEmployeeIdAndStatus(
                workshop.getId(), luis.getId(), SaleStatus.COMPLETED)).isEqualTo(1L);
    }

    // ── Resumen de hoy / ticket promedio ──────────────────────────

    /** Intervalo semiabierto: ayer no cuenta, hoy si. */
    @Test
    void summaryOnlyIncludesSalesCompletedWithinTheBusinessDay() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.minusDays(1).atTime(23, 59, 59),
                TODAY.minusDays(1).atTime(23, 59, 59), "999.00");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atStartOfDay(), TODAY.atStartOfDay(), "40.00");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(23, 59, 59), TODAY.atTime(23, 59, 59), "60.00");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.plusDays(1).atStartOfDay(),
                TODAY.plusDays(1).atStartOfDay(), "999.00");

        SalesSummaryProjection summary =
                repository.summarizeCompletedSales(workshop.getId(), DAY_START, DAY_END);

        assertThat(summary.getSalesCount()).isEqualTo(2L);
        assertThat(summary.getAmount()).isEqualByComparingTo("100.00");
    }

    // ── Tendencia diaria ─────────────────────────────────────────

    @Test
    void dailyTrendGroupsByCompletionDayAndReturnsOnlyDaysWithActivity() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        Employee ana = employee(workshopA, "ANA");
        Employee beto = employee(workshopB, "BETO");
        sale(workshopA, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(8, 0), TODAY.atTime(8, 0), "50.00");
        sale(workshopA, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(20, 0), TODAY.atTime(20, 0), "30.00");
        sale(workshopA, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.minusDays(2).atTime(9, 0),
                TODAY.minusDays(2).atTime(9, 0), "20.00");
        // De otro taller: no debe aparecer.
        sale(workshopB, beto, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(11, 0), TODAY.atTime(11, 0), "999.00");

        List<SalesByDayProjection> rows = repository.findSalesByDay(
                workshopA.getId(), TODAY.minusDays(29).atStartOfDay(), DAY_END);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getSaleDate()).isEqualTo(TODAY.minusDays(2).toString());
        assertThat(rows.get(0).getSalesCount()).isEqualTo(1L);
        assertThat(rows.get(1).getSaleDate()).isEqualTo(TODAY.toString());
        assertThat(rows.get(1).getSalesCount()).isEqualTo(2L);
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo("80.00");
    }

    // ── Productos mas vendidos ────────────────────────────────────

    /** El agrupado es por Product.id, NUNCA por invoiceDescription. */
    @Test
    void topProductsGroupByProductIdNotByInvoiceDescription() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        Product hoja = product("HOJA 900MM");
        Product tornillo = product("TORNILLO 1/2");

        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00",
                detail(hoja, 3, "20.00", "HOJA REFORZADA 900MM"));
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(10, 0), TODAY.atTime(10, 0), "80.00",
                detail(hoja, 2, "20.00", null));
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(11, 0), TODAY.atTime(11, 0), "10.00",
                detail(tornillo, 1, "10.00", null));
        // Ayer: no debe sumar al ranking de hoy.
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.minusDays(1).atTime(9, 0),
                TODAY.minusDays(1).atTime(9, 0), "999.00",
                detail(hoja, 99, "20.00", null));

        List<TopProductProjection> top =
                repository.findTopProductsToday(workshop.getId(), DAY_START, DAY_END, 10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).getProductId()).isEqualTo(hoja.getId());
        assertThat(top.get(0).getProductName()).isEqualTo("HOJA 900MM");
        // Dos ventas distintas con descripciones de factura distintas se suman igual.
        assertThat(top.get(0).getQuantity()).isEqualTo(5L);
        assertThat(top.get(1).getProductId()).isEqualTo(tornillo.getId());
        assertThat(top.get(1).getQuantity()).isEqualTo(1L);
    }

    // ── Metodos de pago ──────────────────────────────────────────

    /** Solo cobros REALES de hoy: una cambiaria sin cobrar nunca aparece aqui. */
    @Test
    void paymentMethodsOnlyIncludeSalesActuallyPaidToday() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CARD, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "50.00");
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PAID, PaymentMethod.TRANSFER, TODAY.minusDays(3).atTime(9, 0),
                TODAY.atTime(15, 0), "70.00");
        // Cambiaria emitida hoy pero AUN pendiente de cobro: no debe aparecer.
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null, TODAY.atTime(9, 0), null, "300.00");
        // Cobrada ayer: no cuenta para hoy.
        sale(workshop, ana, SaleStatus.COMPLETED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.minusDays(1).atTime(9, 0),
                TODAY.minusDays(1).atTime(9, 0), "999.00");

        List<PaymentMethodStatProjection> methods =
                repository.findPaymentMethodsBetween(workshop.getId(), DAY_START, DAY_END);

        assertThat(methods).hasSize(3);
        assertThat(methods).extracting(PaymentMethodStatProjection::getMethod)
                .containsExactlyInAnyOrder("CASH", "CARD", "TRANSFER");
        assertThat(methods.stream()
                .filter(m -> m.getMethod().equals("CASH"))
                .findFirst().orElseThrow().getAmount())
                .isEqualByComparingTo("100.00");
    }

    /**
     * El conteo de exito ya prueba que REVERSED no cuenta como completada
     * ({@link #onlyCompletedSalesCountAsSuccessful}); esta prueba cierra el
     * mismo requisito para el ranking de productos y la distribucion de pago,
     * que se calculan con consultas SQL independientes y podrian, en teoria,
     * filtrar el estado de forma distinta.
     */
    @Test
    void reversedSalesDoNotLeakIntoTopProductsOrPaymentMethods() {
        Workshop workshop = workshop("TALLER A");
        Employee ana = employee(workshop, "ANA");
        Product hoja = product("HOJA 900MM");
        sale(workshop, ana, SaleStatus.REVERSED, InvoiceType.INVOICE, PaymentStatus.PAID,
                PaymentMethod.CASH, TODAY.atTime(9, 0), TODAY.atTime(9, 0), "500.00",
                detail(hoja, 99, "5.00", null));

        assertThat(repository.findTopProductsToday(workshop.getId(), DAY_START, DAY_END, 10))
                .isEmpty();
        assertThat(repository.findPaymentMethodsBetween(workshop.getId(), DAY_START, DAY_END))
                .isEmpty();
    }

    // ── Sin datos ────────────────────────────────────────────────

    @Test
    void anEmptyWorkshopReturnsZerosAndEmptyLists() {
        Workshop workshop = workshop("TALLER A");
        UUID id = workshop.getId();

        assertThat(repository.countByWorkshopIdAndStatus(id, SaleStatus.COMPLETED)).isZero();
        assertThat(repository.countByWorkshopIdAndStatus(id, SaleStatus.PENDING)).isZero();
        assertThat(repository.summarizeCompletedSales(id, DAY_START, DAY_END).getSalesCount())
                .isZero();
        assertThat(repository.findSalesByDay(id, DAY_START, DAY_END)).isEmpty();
        assertThat(repository.findTopProductsToday(id, DAY_START, DAY_END, 10)).isEmpty();
        assertThat(repository.findPaymentMethodsBetween(id, DAY_START, DAY_END)).isEmpty();
    }

}
