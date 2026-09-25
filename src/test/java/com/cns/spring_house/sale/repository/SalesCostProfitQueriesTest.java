package com.cns.spring_house.sale.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
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
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.projection.SalesCostProfitBucketProjection;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * El agregado de COSTO DE LO VENDIDO y GANANCIA BRUTA contra PostgreSQL real.
 *
 * Lo que se comprueba vive en la sentencia: los `FILTER (WHERE cost_total IS NOT
 * NULL)` que impiden contar como gratis una linea sin costo, la exclusion de los
 * servicios por categoria, el fechado por `completed_at` y el aislamiento por
 * taller. Con un mock del repositorio ninguna de esas reglas se ejercitaria.
 *
 * Los detalles se siembran con su `costTotal` YA puesto, como lo dejo el FIFO al
 * vender: aqui se prueba la lectura, no la captura —de eso se ocupa
 * {@code InventoryAcquisitionFifoDatabaseTest}—.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SalesCostProfitQueriesTest {

    private static final String TEMPORARY_DATABASE = "sales_cost_profit_it";

    private static final LocalDateTime FROM = LocalDate.of(2026, 1, 1).atStartOfDay();
    private static final LocalDateTime TO = LocalDate.of(2027, 1, 1).atStartOfDay();

    @Autowired
    private SaleRepository repository;

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    // ═══ Costo y ganancia ════════════════════════════════════════

    /** Venta Q680, costo Q400: ganancia Q280. El caso de referencia. */
    @Test
    void computesCostAndGrossProfitPerDay() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        Sale sale = completedSale(workshop, at(2026, 3, 10));
        detail(sale, product, 4, "680.00", "400.00");

        SalesCostProfitBucketProjection bucket = buckets(workshop, "day").get(0);

        assertThat(bucket.getBucketDate()).isEqualTo("2026-03-10");
        assertThat(bucket.getCoveredRevenue()).isEqualByComparingTo("680.00");
        assertThat(bucket.getCostOfGoodsSold()).isEqualByComparingTo("400.00");
        assertThat(bucket.getGrossProfit()).isEqualByComparingTo("280.00");
        assertThat(bucket.getCoveredLines()).isEqualTo(1L);
        assertThat(bucket.getUncoveredLines()).isZero();
    }

    /**
     * Dos ventas con costos FIFO distintos suman EXACTAMENTE sus historicos: el
     * agregado no vuelve a calcular nada contra el inventario de hoy.
     */
    @Test
    void sumsDistinctHistoricalCostsExactly() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        Sale first = completedSale(workshop, at(2026, 3, 10));
        detail(first, product, 4, "680.00", "400.00");
        Sale second = completedSale(workshop, at(2026, 3, 11));
        detail(second, product, 8, "1360.00", "840.00");

        List<SalesCostProfitBucketProjection> buckets = buckets(workshop, "month");

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).getCostOfGoodsSold()).isEqualByComparingTo("1240.00");
        assertThat(buckets.get(0).getGrossProfit()).isEqualByComparingTo("800.00");
    }

    @Test
    void groupsByMonthAndYear() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        detail(completedSale(workshop, at(2026, 3, 10)), product, 1, "100.00", "60.00");
        detail(completedSale(workshop, at(2026, 5, 10)), product, 1, "200.00", "150.00");

        List<SalesCostProfitBucketProjection> months = buckets(workshop, "month");
        assertThat(months).hasSize(2);
        assertThat(months.get(0).getBucketDate()).isEqualTo("2026-03-01");
        assertThat(months.get(0).getGrossProfit()).isEqualByComparingTo("40.00");
        assertThat(months.get(1).getBucketDate()).isEqualTo("2026-05-01");
        assertThat(months.get(1).getGrossProfit()).isEqualByComparingTo("50.00");

        List<SalesCostProfitBucketProjection> years = repository
                .findSalesCostProfitBuckets(workshop.getId(), "year",
                        LocalDate.of(2020, 1, 1).atStartOfDay(),
                        LocalDate.of(2030, 1, 1).atStartOfDay());
        assertThat(years).hasSize(1);
        assertThat(years.get(0).getBucketDate()).isEqualTo("2026-01-01");
        assertThat(years.get(0).getGrossProfit()).isEqualByComparingTo("90.00");
    }

    /**
     * El ajuste manual de precio queda reflejado porque la ganancia sale del
     * SUBTOTAL historico y no del precio de catalogo.
     */
    @Test
    void reflectsAManualPriceAdjustment() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        // Se vendio con descuento: 4 unidades por Q500 en vez de Q680.
        detail(completedSale(workshop, at(2026, 3, 10)),
                product, 4, "500.00", "400.00");

        assertThat(buckets(workshop, "day").get(0).getGrossProfit())
                .isEqualByComparingTo("100.00");
    }

    // ═══ Cobertura ═══════════════════════════════════════════════

    /**
     * Una linea sin costo NO suma como cero: se cuenta aparte y su venta viaja
     * en `uncoveredRevenue`. Sumarla como cero convertiria su precio integro en
     * ganancia e inflaria el margen del taller.
     */
    @Test
    void neverTreatsAnUnknownCostAsZero() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        Sale sale = completedSale(workshop, at(2026, 3, 10));
        detail(sale, product, 1, "100.00", "60.00");
        // Mercaderia de produccion: no tiene costo de adquisicion conocido.
        detail(sale, product, 1, "300.00", null);

        SalesCostProfitBucketProjection bucket = buckets(workshop, "day").get(0);

        assertThat(bucket.getCostOfGoodsSold()).isEqualByComparingTo("60.00");
        assertThat(bucket.getGrossProfit()).isEqualByComparingTo("40.00");
        assertThat(bucket.getCoveredRevenue()).isEqualByComparingTo("100.00");
        assertThat(bucket.getCoveredLines()).isEqualTo(1L);
        assertThat(bucket.getUncoveredLines()).isEqualTo(1L);
        assertThat(bucket.getUncoveredRevenue()).isEqualByComparingTo("300.00");
    }

    /**
     * Un servicio queda fuera POR CATEGORIA. No tiene costo de adquisicion en
     * este modelo, asi que contarlo como linea descubierta ensuciaria la
     * cobertura con algo que nunca podra cubrirse.
     */
    @Test
    void excludesServicesEntirely() {
        Workshop workshop = workshop();
        Product goods = product(workshop, FamilyCategory.NORMAL);
        Product service = product(workshop, FamilyCategory.JOB);

        Sale sale = completedSale(workshop, at(2026, 3, 10));
        detail(sale, goods, 1, "100.00", "60.00");
        detail(sale, service, 1, "500.00", null);

        SalesCostProfitBucketProjection bucket = buckets(workshop, "day").get(0);

        assertThat(bucket.getCoveredLines()).isEqualTo(1L);
        assertThat(bucket.getUncoveredLines()).isZero();
        assertThat(bucket.getUncoveredRevenue()).isEqualByComparingTo("0.00");
        assertThat(bucket.getGrossProfit()).isEqualByComparingTo("40.00");
    }

    // ═══ Universo de ventas ══════════════════════════════════════

    /**
     * CANCELLED y REVERSED no son venta: no aportan ni costo ni ganancia. Es el
     * mismo criterio que el resto de agregados del panel.
     */
    @Test
    void excludesCancelledAndReversedSales() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        detail(completedSale(workshop, at(2026, 3, 10)), product, 1, "100.00", "60.00");
        detail(saleWith(workshop, SaleStatus.CANCELLED, at(2026, 3, 10)),
                product, 1, "999.00", "1.00");
        detail(saleWith(workshop, SaleStatus.REVERSED, at(2026, 3, 10)),
                product, 1, "999.00", "1.00");
        detail(saleWith(workshop, SaleStatus.PENDING, null),
                product, 1, "999.00", "1.00");

        SalesCostProfitBucketProjection bucket = buckets(workshop, "day").get(0);

        assertThat(bucket.getCostOfGoodsSold()).isEqualByComparingTo("60.00");
        assertThat(bucket.getGrossProfit()).isEqualByComparingTo("40.00");
    }

    /**
     * Una cambiaria emitida y NO cobrada SI cuenta: ya entrego mercaderia y ya
     * genero margen. Esta serie mide rendimiento de venta, no caja.
     */
    @Test
    void includesIssuedButUnpaidExchangeInvoices() {
        Workshop workshop = workshop();
        Product product = product(workshop, FamilyCategory.NORMAL);

        Sale exchange = entityManager.persistAndFlush(Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(at(2026, 3, 10))
                .total(new BigDecimal("100.00"))
                .build());
        detail(exchange, product, 1, "100.00", "60.00");

        assertThat(buckets(workshop, "day").get(0).getGrossProfit())
                .isEqualByComparingTo("40.00");
    }

    @Test
    void neverMixesSalesFromAnotherWorkshop() {
        Workshop workshop = workshop();
        detail(completedSale(workshop, at(2026, 3, 10)),
                product(workshop, FamilyCategory.NORMAL), 1, "100.00", "60.00");

        Workshop other = workshop();
        detail(completedSale(other, at(2026, 3, 10)),
                product(other, FamilyCategory.NORMAL), 1, "999.00", "1.00");

        assertThat(buckets(workshop, "day").get(0).getGrossProfit())
                .isEqualByComparingTo("40.00");
    }

    @Test
    void returnsNothingForARangeWithoutSales() {
        Workshop workshop = workshop();
        detail(completedSale(workshop, at(2026, 3, 10)),
                product(workshop, FamilyCategory.NORMAL), 1, "100.00", "60.00");

        assertThat(repository.findSalesCostProfitBuckets(workshop.getId(), "day",
                LocalDate.of(2026, 6, 1).atStartOfDay(),
                LocalDate.of(2026, 7, 1).atStartOfDay())).isEmpty();
    }

    // ── Apoyo ────────────────────────────────────────────────────

    private List<SalesCostProfitBucketProjection> buckets(
            Workshop workshop, String unit) {
        return repository.findSalesCostProfitBuckets(
                workshop.getId(), unit, FROM, TO);
    }

    private static LocalDateTime at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atTime(14, 0);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Workshop workshop() {
        return entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .isActive(true)
                .build());
    }

    private Employee employee(Workshop workshop) {
        return entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    private Product product(Workshop workshop, FamilyCategory category) {
        String code = unique();
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .build());
        return entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name("PRODUCTO " + code)
                .attributes(new HashMap<>())
                .build());
    }

    private Sale completedSale(Workshop workshop, LocalDateTime completedAt) {
        return saleWith(workshop, SaleStatus.COMPLETED, completedAt);
    }

    private Sale saleWith(
            Workshop workshop, SaleStatus status, LocalDateTime completedAt) {
        return entityManager.persistAndFlush(Sale.builder()
                .taxMode(status == SaleStatus.PENDING ? TaxMode.UNRESOLVED : TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(status)
                .invoiceType(status == SaleStatus.PENDING ? null : InvoiceType.INVOICE)
                .paymentStatus(status == SaleStatus.COMPLETED
                        ? PaymentStatus.PAID : PaymentStatus.PENDING)
                .paymentMethod(status == SaleStatus.COMPLETED
                        ? PaymentMethod.CASH : null)
                .completedAt(completedAt)
                .paidAt(status == SaleStatus.COMPLETED ? completedAt : null)
                .total(BigDecimal.ZERO)
                .build());
    }

    /** `costTotal` null representa una linea sin costo de adquisicion conocido. */
    private SaleDetail detail(
            Sale sale, Product product, int quantity,
            String subTotal, String costTotal) {
        return entityManager.persistAndFlush(SaleDetail.builder()
                .sale(sale)
                .product(product)
                .quantity(quantity)
                .price(new BigDecimal(subTotal)
                        .divide(BigDecimal.valueOf(quantity)))
                .subTotal(new BigDecimal(subTotal))
                .costTotal(costTotal == null ? null : new BigDecimal(costTotal))
                .build());
    }

}
