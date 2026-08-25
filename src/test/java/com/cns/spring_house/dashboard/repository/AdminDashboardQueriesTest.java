package com.cns.spring_house.dashboard.repository;

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

import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.repository.projection.ProductSalesProjection;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.projection.EmployeePerformanceProjection;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.CustomerPerformanceProjection;
import com.cns.spring_house.sale.repository.projection.ProductPerformanceProjection;
import com.cns.spring_house.sale.repository.projection.SalesBucketProjection;
import com.cns.spring_house.sale.repository.projection.SalesSummaryProjection;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.projection.OccupancyByDayProjection;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Ejecuta los agregados del panel administrativo contra PostgreSQL real sobre
 * una base temporal creada por la propia prueba, igual que
 * {@link com.cns.spring_house.sale.repository.SaleDashboardQueriesTest}.
 *
 * Se prueban aqui y no con mocks porque lo que se comprueba ES el SQL: la
 * distincion entre facturado y cobrado, el `generate_series` de la ocupacion,
 * el LEFT JOIN que conserva los productos sin ventas y el aislamiento por
 * taller ocurren todos DENTRO de la sentencia. Un mock del repositorio
 * confirmaria unicamente que el servicio llama al metodo.
 *
 * Cada prueba monta un taller propio: asi ninguna depende del orden de
 * ejecucion ni de datos que otra haya dejado.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AdminDashboardQueriesTest {

    private static final String TEMPORARY_DATABASE = "admin_dashboard_it";

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15);
    private static final LocalDateTime NOW = TODAY.atTime(18, 0);

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private VehicleMovementRepository vehicleMovementRepository;

    @Autowired
    private JobLogRepository jobLogRepository;

    @Autowired
    private TransformationRepository transformationRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

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

    // ── Ventas generadas ─────────────────────────────────────────

    /**
     * La regla del panel: cuenta lo VENDIDO, no lo cobrado.
     *
     * La cambiaria emitida y pendiente de cobro SI suma, que es exactamente lo
     * que la separa del cierre de caja. PENDING no suma —es un carrito abierto,
     * todavia editable, que no ha generado ninguna venta—, y CANCELLED y
     * REVERSED tampoco: una nunca llego a venderse y la otra se deshizo.
     */
    @Test
    void generatedSalesCountCompletedSalesRegardlessOfPayment() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");

        // Factura normal cobrada.
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        // Cambiaria emitida y NO cobrada: la venta YA se genero.
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null,
                TODAY.atTime(10, 0), null, "5000.00");

        SalesSummaryProjection generated = saleRepository
                .summarizeGeneratedSales(workshop.getId());

        assertThat(generated.getSalesCount()).isEqualTo(2L);
        assertThat(generated.getAmount()).isEqualByComparingTo("5100.00");
    }

    @Test
    void generatedSalesExcludePendingCancelledAndReversed() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        sale(workshop, employee, SaleStatus.PENDING, null,
                PaymentStatus.PENDING, null, null, null, "300.00");
        sale(workshop, employee, SaleStatus.CANCELLED, null,
                PaymentStatus.PENDING, null, null, null, "700.00");
        sale(workshop, employee, SaleStatus.REVERSED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(11, 0), TODAY.atTime(11, 0), "900.00");

        SalesSummaryProjection generated = saleRepository
                .summarizeGeneratedSales(workshop.getId());

        assertThat(generated.getSalesCount()).isEqualTo(1L);
        assertThat(generated.getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void generatedSalesAreIsolatedByWorkshop() {
        Workshop mine = workshop();
        Workshop other = workshop();
        sale(mine, employee(mine, "ANA"), SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00");
        sale(other, employee(other, "BEA"), SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "8000.00");

        assertThat(saleRepository.summarizeGeneratedSales(mine.getId()).getAmount())
                .isEqualByComparingTo("100.00");
    }

    /**
     * La serie se fecha por `completed_at` —cuando se EMITIO la venta— y no por
     * `paid_at`. Una cambiaria emitida en julio y cobrada en agosto pertenece al
     * punto de JULIO: en el mes en que se genero la venta. Fechar por el cobro
     * la moveria al mes equivocado del rendimiento comercial, y ademas dejaria
     * fuera de la serie a las que nunca se cobran.
     */
    @Test
    void salesBucketsUseTheIssueDateAndNotThePaymentDate() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PAID, PaymentMethod.TRANSFER,
                LocalDate.of(2026, 7, 20).atTime(9, 0), TODAY.atTime(9, 0), "400.00");

        List<SalesBucketProjection> august = saleRepository.findGeneratedSalesBuckets(
                workshop.getId(), "day",
                LocalDate.of(2026, 8, 1).atStartOfDay(),
                LocalDate.of(2026, 9, 1).atStartOfDay());
        assertThat(august).isEmpty();

        List<SalesBucketProjection> july = saleRepository.findGeneratedSalesBuckets(
                workshop.getId(), "day",
                LocalDate.of(2026, 7, 1).atStartOfDay(),
                LocalDate.of(2026, 8, 1).atStartOfDay());

        assertThat(july).singleElement().satisfies(bucket -> {
            assertThat(bucket.getBucketDate()).isEqualTo("2026-07-20");
            assertThat(bucket.getAmount()).isEqualByComparingTo("400.00");
        });
    }

    /** Una cambiaria NUNCA cobrada sigue apareciendo en la serie. */
    @Test
    void salesBucketsIncludeAnUnpaidExchangeInvoice() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null,
                TODAY.atTime(10, 0), null, "1500.00");

        List<SalesBucketProjection> buckets = saleRepository.findGeneratedSalesBuckets(
                workshop.getId(), "day",
                LocalDate.of(2026, 8, 1).atStartOfDay(),
                LocalDate.of(2026, 9, 1).atStartOfDay());

        assertThat(buckets).singleElement().satisfies(bucket -> {
            assertThat(bucket.getBucketDate()).isEqualTo("2026-08-15");
            assertThat(bucket.getAmount()).isEqualByComparingTo("1500.00");
        });
    }

    @Test
    void salesBucketsGroupByDayMonthAndYear() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");

        completed(workshop, employee, LocalDate.of(2026, 3, 4).atTime(9, 0), "100.00");
        completed(workshop, employee, LocalDate.of(2026, 3, 20).atTime(9, 0), "50.00");
        completed(workshop, employee, LocalDate.of(2025, 11, 2).atTime(9, 0), "700.00");

        List<SalesBucketProjection> days = saleRepository.findGeneratedSalesBuckets(
                workshop.getId(), "day",
                LocalDate.of(2026, 3, 1).atStartOfDay(),
                LocalDate.of(2026, 4, 1).atStartOfDay());
        assertThat(days).extracting(SalesBucketProjection::getBucketDate)
                .containsExactly("2026-03-04", "2026-03-20");

        List<SalesBucketProjection> months = saleRepository.findGeneratedSalesBuckets(
                workshop.getId(), "month",
                LocalDate.of(2026, 1, 1).atStartOfDay(),
                LocalDate.of(2027, 1, 1).atStartOfDay());
        assertThat(months).singleElement().satisfies(bucket -> {
            assertThat(bucket.getBucketDate()).isEqualTo("2026-03-01");
            assertThat(bucket.getSalesCount()).isEqualTo(2L);
            assertThat(bucket.getAmount()).isEqualByComparingTo("150.00");
        });

        List<SalesBucketProjection> years = saleRepository.findGeneratedSalesBuckets(
                workshop.getId(), "year",
                LocalDate.of(2020, 1, 1).atStartOfDay(),
                LocalDate.of(2027, 1, 1).atStartOfDay());
        assertThat(years).extracting(SalesBucketProjection::getBucketDate)
                .containsExactly("2025-01-01", "2026-01-01");
    }

    // ── Ocupacion del taller ─────────────────────────────────────

    /**
     * La ocupacion NO es "entradas del dia": el 14 no entro ningun vehiculo y
     * sin embargo cerro con dos dentro, porque los dos habian entrado el 13.
     */
    @Test
    void occupancyCountsVehiclesStillInsideAtTheEndOfEachDay() {
        Workshop workshop = workshop();

        // Entra el 13, sale el 14: dentro al cierre del 13, fuera al cierre del 14.
        movement(workshop, "P111AAA",
                LocalDate.of(2026, 8, 13).atTime(8, 0),
                LocalDate.of(2026, 8, 14).atTime(16, 0));
        // Entra el 13 y sigue dentro.
        movement(workshop, "P222BBB",
                LocalDate.of(2026, 8, 13).atTime(9, 0), null);
        // Entra el 14 y sigue dentro.
        movement(workshop, "P333CCC",
                LocalDate.of(2026, 8, 14).atTime(10, 0), null);

        List<OccupancyByDayProjection> series = vehicleMovementRepository.findOccupancyByDay(
                workshop.getId(),
                LocalDate.of(2026, 8, 12),
                TODAY,
                NOW);

        assertThat(series).extracting(
                OccupancyByDayProjection::getBucketDate,
                OccupancyByDayProjection::getVehicles)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("2026-08-12", 0L),
                        org.assertj.core.api.Assertions.tuple("2026-08-13", 2L),
                        org.assertj.core.api.Assertions.tuple("2026-08-14", 2L),
                        org.assertj.core.api.Assertions.tuple("2026-08-15", 2L));
    }

    /**
     * El ultimo punto se corta en `seriesEnd` y no en la medianoche que aun no
     * ha llegado, de modo que coincide EXACTAMENTE con el KPI de vehiculos
     * dentro. Un vehiculo que entro y salio hoy no aparece en ninguno de los
     * dos.
     */
    @Test
    void todayOccupancyMatchesTheCurrentVehiclesKpi() {
        Workshop workshop = workshop();
        movement(workshop, "P444DDD", TODAY.atTime(7, 0), TODAY.atTime(12, 0));
        movement(workshop, "P555EEE", TODAY.atTime(8, 0), null);

        List<OccupancyByDayProjection> series = vehicleMovementRepository
                .findOccupancyByDay(workshop.getId(), TODAY, TODAY, NOW);

        assertThat(series).singleElement()
                .extracting(OccupancyByDayProjection::getVehicles)
                .isEqualTo(1L);
        assertThat(vehicleMovementRepository.countByWorkshopIdAndExitIsNull(workshop.getId()))
                .isEqualTo(1L);
    }

    @Test
    void occupancyIsIsolatedByWorkshop() {
        Workshop mine = workshop();
        Workshop other = workshop();
        movement(other, "P666FFF", LocalDate.of(2026, 8, 10).atTime(8, 0), null);

        List<OccupancyByDayProjection> series = vehicleMovementRepository
                .findOccupancyByDay(mine.getId(), TODAY, TODAY, NOW);

        assertThat(series).singleElement()
                .extracting(OccupancyByDayProjection::getVehicles)
                .isEqualTo(0L);
    }

    /**
     * El KPI de vehiculos usa `COUNT(*)`, y eso solo es exacto si la base impide
     * que un mismo vehiculo tenga dos entradas abiertas en el taller.
     *
     * El indice parcial `uq_vehicle_movement_open_plate_per_workshop`
     * (V19: `UNIQUE(workshop_id, UPPER(plate)) WHERE exit IS NULL`) es esa
     * garantia. Aqui se comprueba que sigue viva: intentar la segunda entrada
     * abierta de la misma placa FALLA, de modo que `COUNT(*)` y "vehiculos
     * distintos dentro" no pueden divergir.
     *
     * Esta prueba es la que justifica no cambiar el KPI a `COUNT(DISTINCT
     * plate)`: contar placas distintas daria la cifra correcta ENCIMA de datos
     * corruptos, ocultando el problema de integridad en lugar de impedirlo.
     */
    @Test
    void theOpenMovementInvariantIsWhatMakesTheVehicleCountExact() {
        Workshop workshop = workshop();
        movement(workshop, "P999ZZZ", TODAY.atTime(8, 0), null);

        // Un vehiculo dentro, una fila abierta: las dos magnitudes coinciden.
        assertThat(vehicleMovementRepository
                .countByWorkshopIdAndExitIsNull(workshop.getId())).isEqualTo(1L);

        // Y no pueden dejar de coincidir: la base rechaza la segunda entrada
        // abierta de la misma placa, asi que `COUNT(*)` nunca puede contar dos
        // filas para un solo vehiculo.
        //
        // No se vuelve a consultar despues de la violacion: PostgreSQL aborta la
        // transaccion en curso y cualquier sentencia posterior fallaria por eso
        // y no por el invariante. Se comprueba el rechazo, que es el hecho.
        VehicleMovement duplicate = VehicleMovement.builder()
                .workshop(workshop)
                .plate(openPlateOf(workshop))
                .entry(TODAY.atTime(9, 0))
                .build();

        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .rootCause()
                .hasMessageContaining("duplicate key value violates unique constraint")
                .hasMessageContaining("uq_vehicle_movement_open_plate_per_workshop");
    }

    /**
     * Cerrada la salida, la misma placa puede volver a entrar y el historico
     * conserva los DOS intervalos por separado. El KPI cuenta solo el abierto.
     */
    @Test
    void aVehicleMayReenterAfterItsExitAndHistoryKeepsBothIntervals() {
        Workshop workshop = workshop();
        String plate = "P777YYY";

        entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshop).plate(plate)
                .entry(LocalDate.of(2026, 8, 10).atTime(8, 0))
                .exit(LocalDate.of(2026, 8, 11).atTime(17, 0))
                .build());
        entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshop).plate(plate)
                .entry(LocalDate.of(2026, 8, 14).atTime(8, 0))
                .build());

        assertThat(vehicleMovementRepository
                .countByWorkshopIdAndExitIsNull(workshop.getId())).isEqualTo(1L);

        List<OccupancyByDayProjection> series = vehicleMovementRepository.findOccupancyByDay(
                workshop.getId(), LocalDate.of(2026, 8, 10), TODAY, NOW);

        assertThat(series).extracting(
                OccupancyByDayProjection::getBucketDate,
                OccupancyByDayProjection::getVehicles)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("2026-08-10", 1L),
                        org.assertj.core.api.Assertions.tuple("2026-08-11", 0L),
                        org.assertj.core.api.Assertions.tuple("2026-08-12", 0L),
                        org.assertj.core.api.Assertions.tuple("2026-08-13", 0L),
                        org.assertj.core.api.Assertions.tuple("2026-08-14", 1L),
                        org.assertj.core.api.Assertions.tuple("2026-08-15", 1L));

        // El ultimo punto y el KPI dicen lo mismo.
        assertThat(series.get(series.size() - 1).getVehicles()).isEqualTo(
                vehicleMovementRepository.countByWorkshopIdAndExitIsNull(workshop.getId()));
    }

    // ── Productos ────────────────────────────────────────────────

    @Test
    void topProductsRankByUnitsAndExcludeCancelledAndReversedSales() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product tornillo = product("TORNILLO");
        Product resorte = product("RESORTE");
        Product anulado = product("ANULADO");

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "300.00",
                detail(tornillo, 10, "10.00"));
        // Cambiaria pendiente de cobro: la mercaderia SI salio, cuenta en unidades.
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.EXCHANGE_INVOICE,
                PaymentStatus.PENDING, null,
                TODAY.atTime(10, 0), null, "200.00",
                detail(resorte, 4, "50.00"));
        sale(workshop, employee, SaleStatus.CANCELLED, null,
                PaymentStatus.PENDING, null, null, null, "999.00",
                detail(anulado, 99, "10.00"));
        sale(workshop, employee, SaleStatus.REVERSED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(11, 0), TODAY.atTime(11, 0), "999.00",
                detail(anulado, 99, "10.00"));

        List<ProductPerformanceProjection> ranking = saleRepository
                .findTopSellingProducts(workshop.getId(), 10);

        assertThat(ranking).extracting(ProductPerformanceProjection::getProductName)
                .containsExactly("TORNILLO", "RESORTE");
        assertThat(ranking.get(0).getQuantity()).isEqualTo(10L);
        assertThat(ranking.get(0).getGeneratedAmount()).isEqualByComparingTo("100.00");
    }

    /**
     * El monto generado es el importe HISTORICO de la linea y NO se recalcula
     * desde el catalogo.
     *
     * Se vende a un precio rebajado respecto al de inventario y despues se sube
     * el precio del inventario: el ranking tiene que seguir mostrando lo que se
     * cobro aquel dia. Si se reconstruyera desde `inventory.price`, subir hoy un
     * precio reescribiria las ventas del mes pasado.
     *
     * Tampoco interviene ningun costo: la cifra es lo que la venta genero, no un
     * margen. El producto tiene costo en inventario y aun asi el importe es el
     * bruto de la linea.
     */
    @Test
    void generatedAmountIsTheHistoricalLineAmountAndIgnoresCost() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product product = product("RESORTE");
        Inventory inventory = inventory(workshop, product, "100.00", "60.00");

        // Vendido con rebaja manual: 80.00 en lugar de los 100.00 del catalogo.
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "160.00",
                detail(product, 2, "80.00"));

        // Despues de la venta cambian precio y costo del inventario.
        inventory.setPrice(new BigDecimal("250.00"));
        inventory.setCost(new BigDecimal("240.00"));
        entityManager.persistAndFlush(inventory);
        entityManager.clear();

        List<ProductPerformanceProjection> ranking = saleRepository
                .findTopSellingProducts(workshop.getId(), 10);

        assertThat(ranking).singleElement().satisfies(row -> {
            assertThat(row.getQuantity()).isEqualTo(2L);
            // 2 x 80.00 historicos. Ni 2 x 250.00 (precio nuevo) ni el margen.
            assertThat(row.getGeneratedAmount()).isEqualByComparingTo("160.00");
        });
    }

    /**
     * Un servicio no tiene inventario ni costo en el modelo, y aun asi aparece
     * con su monto generado completo: la consulta ya no se une con `inventory`,
     * de modo que no hay nada que pueda dejarlo fuera ni dejarlo sin importe.
     */
    @Test
    void generatedAmountCoversProductsWithoutInventory() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product servicio = product("SERVICIO");

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "100.00",
                detail(servicio, 1, "100.00"));

        assertThat(saleRepository.findTopSellingProducts(workshop.getId(), 10))
                .singleElement()
                .satisfies(row -> assertThat(row.getGeneratedAmount())
                        .isEqualByComparingTo("100.00"));
    }

    /**
     * Lo menos vendido incluye lo que NUNCA se vendio: si no lo incluyera, el
     * ranking no podria senalar el stock parado, que es para lo que existe.
     */
    @Test
    void leastSellingProductsIncludeUnsoldStockAsZero() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product vendido = product("VENDIDO");
        Product parado = product("PARADO");
        inventory(workshop, vendido, "10.00", "5.00");
        inventory(workshop, parado, "10.00", "5.00");

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "70.00",
                detail(vendido, 7, "10.00"));

        List<ProductSalesProjection> ranking = inventoryRepository
                .findLeastSellingProducts(workshop.getId(), 5);

        assertThat(ranking).extracting(
                ProductSalesProjection::getProductName, ProductSalesProjection::getQuantity)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("PARADO", 0L),
                        org.assertj.core.api.Assertions.tuple("VENDIDO", 7L));
    }

    /**
     * Un producto que este taller no maneja no es stock parado SUYO, y las
     * ventas de otro taller no pueden sumar en su rotacion.
     */
    @Test
    void leastSellingProductsAreIsolatedByWorkshop() {
        Workshop mine = workshop();
        Workshop other = workshop();
        Product compartido = product("COMPARTIDO");
        Product ajeno = product("AJENO");
        inventory(mine, compartido, "10.00", "5.00");
        inventory(other, ajeno, "10.00", "5.00");

        sale(other, employee(other, "BEA"), SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "90.00",
                detail(compartido, 9, "10.00"));

        assertThat(inventoryRepository.findLeastSellingProducts(mine.getId(), 5))
                .extracting(
                        ProductSalesProjection::getProductName,
                        ProductSalesProjection::getQuantity)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("COMPARTIDO", 0L));
    }

    /**
     * Dos productos DISTINTOS con el mismo nombre y las mismas unidades.
     *
     * No es un caso inventado: aparecio en el catalogo real del taller, con dos
     * "HOJA DE RESORTE 1200" empatados a cero unidades ocupando los puestos 3 y
     * 4. Con el nombre como ultima clave de orden, PostgreSQL podia devolverlos
     * en cualquier orden y el ranking cambiaba entre dos peticiones identicas.
     *
     * Se pide varias veces y se exige la MISMA secuencia de ids: es lo unico que
     * distingue un orden total de uno que casualmente coincidio.
     */
    @Test
    void rankingsAreDeterministicForProductsSharingAName() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product first = product("HOJA DE RESORTE 1200");
        Product second = product("HOJA DE RESORTE 1200");
        inventory(workshop, first, "10.00", "5.00");
        inventory(workshop, second, "10.00", "5.00");

        List<UUID> expectedLeast = inventoryRepository
                .findLeastSellingProducts(workshop.getId(), 5)
                .stream()
                .map(ProductSalesProjection::getProductId)
                .toList();

        assertThat(expectedLeast).hasSize(2);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(inventoryRepository.findLeastSellingProducts(workshop.getId(), 5))
                    .extracting(ProductSalesProjection::getProductId)
                    .containsExactlyElementsOf(expectedLeast);
        }

        // Mismo empate por el otro extremo: iguales en unidades e importe.
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "60.00",
                detail(first, 3, "10.00"),
                detail(second, 3, "10.00"));

        List<UUID> expectedTop = saleRepository
                .findTopSellingProducts(workshop.getId(), 10)
                .stream()
                .map(ProductPerformanceProjection::getProductId)
                .toList();

        assertThat(expectedTop).hasSize(2);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(saleRepository.findTopSellingProducts(workshop.getId(), 10))
                    .extracting(ProductPerformanceProjection::getProductId)
                    .containsExactlyElementsOf(expectedTop);
        }
    }

    /**
     * El ranking se titula "productos mas vendidos" y tiene que significarlo.
     *
     * Un servicio tambien apunta a un `Product` —`JobSaleDetailHandler` arma la
     * linea con `job.getProduct()`—, asi que sin filtro por categoria la mano de
     * obra compite por los puestos. El montaje pone al servicio como lo MAS
     * vendido con diferencia (50 unidades frente a 10 y 5): si el filtro
     * desapareciera, encabezaria el ranking y esta prueba fallaria por el primer
     * puesto, no por un detalle del final de la lista.
     */
    @Test
    void topProductsExcludeServiceLinesEvenWhenTheyAreTheBestSelling() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product servicio = product("INSTALACION", FamilyCategory.JOB);
        Product tornillo = product("TORNILLO", FamilyCategory.NORMAL);
        Product hoja = product("HOJA DE RESORTE", FamilyCategory.SPECIAL);

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "5750.00",
                detail(servicio, 50, "100.00"),
                detail(tornillo, 10, "50.00"),
                detail(hoja, 5, "50.00"));

        List<ProductPerformanceProjection> ranking = saleRepository
                .findTopSellingProducts(workshop.getId(), 10);

        assertThat(ranking).extracting(ProductPerformanceProjection::getProductName)
                .containsExactly("TORNILLO", "HOJA DE RESORTE")
                .doesNotContain("INSTALACION");
    }

    /**
     * Las tres categorias de mercaderia SI entran. Excluir servicios no puede
     * llevarse por delante nada de lo que el taller vende como pieza.
     */
    @Test
    void topProductsIncludeEveryNonServiceCategory() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product normal = product("NORMAL", FamilyCategory.NORMAL);
        Product special = product("SPECIAL", FamilyCategory.SPECIAL);
        Product container = product("CONTAINER", FamilyCategory.CONTAINER);

        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "600.00",
                detail(normal, 3, "100.00"),
                detail(special, 2, "100.00"),
                detail(container, 1, "100.00"));

        assertThat(saleRepository.findTopSellingProducts(workshop.getId(), 10))
                .extracting(ProductPerformanceProjection::getProductName)
                .containsExactly("NORMAL", "SPECIAL", "CONTAINER");
    }

    /**
     * La SALIDA de una transformacion se vende como el producto que es.
     *
     * Se monta la cadena real —transformacion con entrada y salida, y la unidad
     * de salida vendida en una linea— para comprobar que el ranking la incluye:
     * haber nacido de una transformacion no la convierte en un servicio, y
     * excluirla borraria del historico justo lo que el taller fabrica.
     */
    @Test
    void topProductsIncludeATransformationOutputSoldAsAProduct() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Product materia = product("MATERIAL LISO", FamilyCategory.SPECIAL);
        Product salida = product("HOJA TRANSFORMADA", FamilyCategory.SPECIAL);

        InventoryItem input = item(inventory(workshop, materia, "100.00", "60.00"));
        InventoryItem output = item(inventory(workshop, salida, "250.00", "0.00"));

        Transformation transformation = entityManager.persistAndFlush(Transformation.builder()
                .workshop(workshop)
                .employee(employee)
                .performedDate(TODAY.atTime(8, 0))
                .build());
        entityManager.persistAndFlush(TransformationItem.builder()
                .transformation(transformation)
                .item(input)
                .role(TransformationItemRole.INPUT)
                .build());
        entityManager.persistAndFlush(TransformationItem.builder()
                .transformation(transformation)
                .item(output)
                .role(TransformationItemRole.OUTPUT)
                .build());

        SaleDetail line = detail(salida, 1, "250.00");
        line.setItem(output);
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(10, 0), TODAY.atTime(10, 0), "250.00", line);

        assertThat(saleRepository.findTopSellingProducts(workshop.getId(), 10))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getProductName()).isEqualTo("HOJA TRANSFORMADA");
                    assertThat(row.getQuantity()).isEqualTo(1L);
                    assertThat(row.getGeneratedAmount()).isEqualByComparingTo("250.00");
                });
    }

    /**
     * Los dos rankings comparten universo: lo que uno considera producto, el
     * otro tambien. Un servicio con inventario —posible en el esquema, aunque el
     * dominio no lo produzca— no puede colarse en el ranking de rotacion baja.
     */
    @Test
    void leastSellingProductsExcludeServicesEvenWithAnInventoryRow() {
        Workshop workshop = workshop();
        Product servicio = product("INSTALACION", FamilyCategory.JOB);
        Product tornillo = product("TORNILLO", FamilyCategory.NORMAL);
        inventory(workshop, servicio, "100.00", "0.00");
        inventory(workshop, tornillo, "10.00", "5.00");

        assertThat(inventoryRepository.findLeastSellingProducts(workshop.getId(), 5))
                .extracting(ProductSalesProjection::getProductName)
                .containsExactly("TORNILLO")
                .doesNotContain("INSTALACION");
    }

    // ── Clientes ─────────────────────────────────────────────────

    /**
     * El ranking de clientes sigue la MISMA regla que el KPI de monto generado:
     * ventas COMPLETED, sin mirar el cobro. La cambiaria pendiente cuenta, y por
     * eso las dos cifras del panel son comparables entre si.
     */
    @Test
    void topCustomersCountCompletedSalesIncludingUnpaidExchangeInvoices() {
        Workshop workshop = workshop();
        Employee employee = employee(workshop, "ANA");
        Customer conCobro = customer("CLIENTE COBRADO");
        Customer conCartera = customer("CLIENTE CARTERA");

        Sale first = sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH,
                TODAY.atTime(9, 0), TODAY.atTime(9, 0), "300.00");
        first.setCustomer(conCobro);
        entityManager.persistAndFlush(first);

        Sale second = sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CARD,
                TODAY.atTime(10, 0), TODAY.atTime(10, 0), "200.00");
        second.setCustomer(conCobro);
        entityManager.persistAndFlush(second);

        // Cambiaria emitida sin cobrar: genera venta, asi que encabeza.
        Sale unpaid = sale(workshop, employee, SaleStatus.COMPLETED,
                InvoiceType.EXCHANGE_INVOICE, PaymentStatus.PENDING, null,
                TODAY.atTime(11, 0), null, "9000.00");
        unpaid.setCustomer(conCartera);
        entityManager.persistAndFlush(unpaid);

        // Cancelada: no genera nada, ni para el conteo de compras.
        Sale cancelled = sale(workshop, employee, SaleStatus.CANCELLED, null,
                PaymentStatus.PENDING, null, null, null, "500.00");
        cancelled.setCustomer(conCobro);
        entityManager.persistAndFlush(cancelled);

        List<CustomerPerformanceProjection> ranking = saleRepository
                .findTopCustomersByGeneratedSales(workshop.getId(), 5);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).getCustomerName()).isEqualTo("CLIENTE CARTERA");
        assertThat(ranking.get(0).getGeneratedAmount()).isEqualByComparingTo("9000.00");
        assertThat(ranking.get(1).getCustomerName()).isEqualTo("CLIENTE COBRADO");
        assertThat(ranking.get(1).getPurchaseCount()).isEqualTo(2L);
        assertThat(ranking.get(1).getGeneratedAmount()).isEqualByComparingTo("500.00");
    }

    // ── Empleados ────────────────────────────────────────────────

    /**
     * Los dos rankings miden hechos DISTINTOS. Quien ejecuta mas servicios no
     * es necesariamente quien hace mas transformaciones, y aqui se comprueba
     * con datos donde el orden se invierte: si uno de los dos reutilizara la
     * consulta del otro, esta prueba fallaria.
     */
    @Test
    void serviceAndTransformationRankingsUseDifferentSources() {
        Workshop workshop = workshop();
        Employee ana = employee(workshop, "ANA");
        Employee beto = employee(workshop, "BETO");
        Job job = job(workshop);

        jobLog(job, ana, TODAY.atTime(8, 0));
        jobLog(job, ana, TODAY.atTime(9, 0));
        jobLog(job, ana, TODAY.atTime(10, 0));
        jobLog(job, beto, TODAY.atTime(11, 0));

        transformation(workshop, beto, TODAY.atTime(8, 0));
        transformation(workshop, beto, TODAY.atTime(9, 0));

        List<EmployeePerformanceProjection> services = jobLogRepository
                .findTopServiceEmployees(workshop.getId(), 5);
        assertThat(services).extracting(
                EmployeePerformanceProjection::getFirstName,
                EmployeePerformanceProjection::getTotal)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("ANA", 3L),
                        org.assertj.core.api.Assertions.tuple("BETO", 1L));

        List<EmployeePerformanceProjection> transformations = transformationRepository
                .findTopTransformationEmployees(workshop.getId(), 5);
        assertThat(transformations).singleElement().satisfies(row -> {
            assertThat(row.getFirstName()).isEqualTo("BETO");
            assertThat(row.getTotal()).isEqualTo(2L);
        });
    }

    @Test
    void employeeRankingsAreIsolatedByWorkshop() {
        Workshop mine = workshop();
        Workshop other = workshop();
        Employee ajeno = employee(other, "AJENO");

        jobLog(job(other), ajeno, TODAY.atTime(8, 0));
        transformation(other, ajeno, TODAY.atTime(8, 0));

        assertThat(jobLogRepository.findTopServiceEmployees(mine.getId(), 5)).isEmpty();
        assertThat(transformationRepository.findTopTransformationEmployees(mine.getId(), 5))
                .isEmpty();
    }

    @Test
    void workerCountIsScopedToTheWorkshop() {
        Workshop mine = workshop();
        Workshop other = workshop();
        employee(mine, "ANA");
        employee(mine, "BETO");
        employee(other, "AJENO");

        assertThat(employeeRepository.countByWorkshopId(mine.getId())).isEqualTo(2L);
    }

    // ── Datos ────────────────────────────────────────────────────

    private int sequence;

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Workshop workshop() {
        return entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
    }

    private Employee employee(Workshop workshop, String firstName) {
        sequence++;
        return entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName(firstName)
                .lastName("APELLIDO")
                .dpi(String.format("%013d", sequence))
                .phoneNumber("12345678")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    private Customer customer(String name) {
        sequence++;
        return entityManager.persistAndFlush(Customer.builder()
                .name(name)
                .nit(String.format("%08d", sequence))
                .build());
    }

    private Product product(String name) {
        return product(name, FamilyCategory.NORMAL);
    }

    /**
     * Producto de una familia con la categoria pedida.
     *
     * La categoria es el discriminador REAL del dominio: es la clave con la que
     * {@code SaleDetailHandlerRegistry} elige como se vende cada linea, y por
     * tanto lo que separa una pieza de un servicio.
     */
    private Product product(String name, FamilyCategory category) {
        String code = unique();
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(code)
                .name("FAMILIA " + code)
                .requiresModel(false)
                .category(category)
                .build());
        return entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name(name)
                .attributes(new HashMap<>())
                .build());
    }

    private Inventory inventory(Workshop workshop, Product product, String price, String cost) {
        return entityManager.persistAndFlush(Inventory.builder()
                .workshop(workshop)
                .product(product)
                .price(new BigDecimal(price))
                .cost(new BigDecimal(cost))
                .stock(0)
                .minStock(0)
                .build());
    }

    /** Una unidad concreta de ese inventario, que es lo que mueve una transformacion. */
    private InventoryItem item(Inventory inventory) {
        return entityManager.persistAndFlush(InventoryItem.builder()
                .inventory(inventory)
                .status(InventoryItemStatus.AVAILABLE)
                .build());
    }

    private Job job(Workshop workshop) {
        return entityManager.persistAndFlush(Job.builder()
                .workshop(workshop)
                .product(product("SERVICIO " + unique()))
                .price(new BigDecimal("50.00"))
                .build());
    }

    private void jobLog(Job job, Employee employee, LocalDateTime performedDate) {
        entityManager.persistAndFlush(JobLog.builder()
                .job(job)
                .employee(employee)
                .performedDate(performedDate)
                .build());
    }

    private void transformation(
            Workshop workshop, Employee employee, LocalDateTime performedDate) {
        entityManager.persistAndFlush(Transformation.builder()
                .workshop(workshop)
                .employee(employee)
                .performedDate(performedDate)
                .build());
    }

    /** La placa del unico movimiento abierto del taller, tal y como se persistio. */
    private String openPlateOf(Workshop workshop) {
        return vehicleMovementRepository.findCurrentVehicles(workshop.getId())
                .get(0)
                .getPlate();
    }

    /**
     * La placa se persiste TAL CUAL, sin sufijo aleatorio.
     *
     * Antes se le añadia uno para asegurar unicidad, pero cada prueba monta su
     * propio taller y el indice unico es por taller, asi que nunca hizo falta.
     * Ademas producia valores como `P111AAAa3f`, que ya no son persistibles:
     * `ck_vehicle_movement_plate_canonical` exige la forma final de la placa.
     */
    private void movement(
            Workshop workshop, String plate, LocalDateTime entry, LocalDateTime exit) {
        entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshop)
                .plate(plate)
                .entry(entry)
                .exit(exit)
                .build());
    }

    private SaleDetail detail(Product product, int quantity, String price) {
        BigDecimal unitPrice = new BigDecimal(price);
        return SaleDetail.builder()
                .product(product)
                .quantity(quantity)
                .price(unitPrice)
                .subTotal(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                .build();
    }

    /** Venta completada en esa fecha. El cobro se deja fuera a proposito. */
    private void completed(
            Workshop workshop, Employee employee, LocalDateTime completedAt, String total) {
        sale(workshop, employee, SaleStatus.COMPLETED, InvoiceType.INVOICE,
                PaymentStatus.PAID, PaymentMethod.CASH, completedAt, completedAt, total);
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

}
