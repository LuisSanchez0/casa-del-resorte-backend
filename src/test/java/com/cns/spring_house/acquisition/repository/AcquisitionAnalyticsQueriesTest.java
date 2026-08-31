package com.cns.spring_house.acquisition.repository;

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

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.projection.AcquisitionBucketProjection;
import com.cns.spring_house.acquisition.repository.projection.SupplierPerformanceProjection;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Los agregados de COMPRAS contra PostgreSQL real.
 *
 * Se prueban aqui y no con mocks porque lo que se comprueba ES el SQL: el
 * `DATE_TRUNC` con la unidad enlazada, los `FILTER (WHERE ...)` que parten el
 * gasto en nacional e importado, la exclusion de los lotes de produccion y el
 * aislamiento por taller ocurren todos DENTRO de la sentencia. Un mock del
 * repositorio confirmaria unicamente que se llamo al metodo.
 *
 * Cada prueba monta su propio taller, asi que ninguna depende del orden de
 * ejecucion.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class AcquisitionAnalyticsQueriesTest {

    private static final String TEMPORARY_DATABASE = "acquisition_analytics_it";

    /** Ventana amplia: las pruebas fechan los lotes a mano dentro de ella. */
    private static final LocalDateTime FROM = LocalDate.of(2026, 1, 1).atStartOfDay();
    private static final LocalDateTime TO = LocalDate.of(2027, 1, 1).atStartOfDay();

    @Autowired
    private InventoryAcquisitionRepository repository;

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
            statement.execute("DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE
                    + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        registry.add("spring.datasource.url", () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
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

    private static String value(
            Properties environment, String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return environment.getProperty(key, fallback);
    }

    // ═══ Serie de gasto ══════════════════════════════════════════

    @Test
    void groupsPurchaseSpendByDay() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        // 10 x 100 = 1000 el dia 10; 5 x 200 = 1000 el mismo dia; 3 x 50 el 12.
        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, supplier, AcquisitionType.IMPORTATION,
                5, "200.00", at(2026, 3, 10));
        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                3, "50.00", at(2026, 3, 12));

        List<AcquisitionBucketProjection> buckets = buckets(workshop, "day", null);

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).getBucketDate()).isEqualTo("2026-03-10");
        assertThat(buckets.get(0).getAmount()).isEqualByComparingTo("2000.00");
        assertThat(buckets.get(0).getAcquisitionCount()).isEqualTo(2L);
        assertThat(buckets.get(0).getUnitsReceived()).isEqualTo(15L);
        assertThat(buckets.get(1).getBucketDate()).isEqualTo("2026-03-12");
        assertThat(buckets.get(1).getAmount()).isEqualByComparingTo("150.00");
    }

    /** La clave mensual se normaliza al PRIMER dia del mes. */
    @Test
    void groupsPurchaseSpendByMonth() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                2, "100.00", at(2026, 3, 28));
        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                1, "300.00", at(2026, 5, 2));

        List<AcquisitionBucketProjection> buckets = buckets(workshop, "month", null);

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).getBucketDate()).isEqualTo("2026-03-01");
        assertThat(buckets.get(0).getAmount()).isEqualByComparingTo("1200.00");
        assertThat(buckets.get(1).getBucketDate()).isEqualTo("2026-05-01");
        assertThat(buckets.get(1).getAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void groupsPurchaseSpendByYear() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                4, "25.00", at(2026, 11, 30));

        List<AcquisitionBucketProjection> buckets = buckets(
                workshop, "year",
                LocalDate.of(2020, 1, 1).atStartOfDay(),
                LocalDate.of(2030, 1, 1).atStartOfDay(), null);

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).getBucketDate()).isEqualTo("2026-01-01");
        assertThat(buckets.get(0).getAmount()).isEqualByComparingTo("1100.00");
    }

    /**
     * El desglose viaja SIEMPRE en la fila, aunque no se filtre: es lo que
     * permite pintar la composicion sin pedir la serie tres veces.
     */
    @Test
    void splitsSpendIntoNationalAndImportation() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, supplier, AcquisitionType.IMPORTATION,
                5, "200.00", at(2026, 3, 10));

        AcquisitionBucketProjection bucket = buckets(workshop, "day", null).get(0);

        assertThat(bucket.getAmount()).isEqualByComparingTo("2000.00");
        assertThat(bucket.getNationalAmount()).isEqualByComparingTo("1000.00");
        assertThat(bucket.getImportationAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void filtersByAcquisitionType() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, supplier, AcquisitionType.IMPORTATION,
                5, "200.00", at(2026, 3, 10));

        assertThat(buckets(workshop, "day", "NATIONAL").get(0).getAmount())
                .isEqualByComparingTo("1000.00");
        assertThat(buckets(workshop, "day", "IMPORTATION").get(0).getAmount())
                .isEqualByComparingTo("1000.00");
        // Sin filtro es la suma de los dos, no una tercera cifra.
        assertThat(buckets(workshop, "day", null).get(0).getAmount())
                .isEqualByComparingTo("2000.00");
    }

    /**
     * Un lote de produccion no es gasto: nadie pago por el. Si sumara, el panel
     * inventaria compras que nunca ocurrieron.
     */
    @Test
    void ignoresProductionLots() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        production(workshop, inventory, 50, at(2026, 3, 10));

        AcquisitionBucketProjection bucket = buckets(workshop, "day", null).get(0);

        assertThat(bucket.getAmount()).isEqualByComparingTo("1000.00");
        assertThat(bucket.getAcquisitionCount()).isEqualTo(1L);
        assertThat(bucket.getUnitsReceived()).isEqualTo(10L);
    }

    @Test
    void returnsNothingForARangeWithoutPurchases() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));

        assertThat(buckets(workshop, "day",
                LocalDate.of(2026, 6, 1).atStartOfDay(),
                LocalDate.of(2026, 7, 1).atStartOfDay(), null)).isEmpty();
    }

    /** El aislamiento por taller es la garantia que no puede fallar. */
    @Test
    void neverMixesSpendFromAnotherWorkshop() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        purchase(workshop, inventory, supplier(workshop, "Propio"),
                AcquisitionType.NATIONAL, 10, "100.00", at(2026, 3, 10));

        Workshop other = workshop();
        Inventory otherInventory = inventory(other);
        purchase(other, otherInventory, supplier(other, "Ajeno"),
                AcquisitionType.NATIONAL, 99, "999.00", at(2026, 3, 10));

        assertThat(buckets(workshop, "day", null).get(0).getAmount())
                .isEqualByComparingTo("1000.00");
    }

    /**
     * Los importes salen de `NUMERIC` y llegan como `BigDecimal` sin pasar por
     * coma flotante: 3 x 0.10 tiene que ser 0.30 exacto, no 0.30000000000000004.
     */
    @Test
    void keepsMoneyExact() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                3, "0.10", at(2026, 3, 10));

        assertThat(buckets(workshop, "day", null).get(0).getAmount())
                .isEqualByComparingTo(new BigDecimal("0.30"));
    }

    // ═══ Top de proveedores ══════════════════════════════════════

    @Test
    void ranksSuppliersByTotalSpend() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);

        Supplier big = supplier(workshop, "Grande");
        Supplier medium = supplier(workshop, "Mediano");
        Supplier small = supplier(workshop, "Pequeno");

        purchase(workshop, inventory, big, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, big, AcquisitionType.IMPORTATION,
                5, "100.00", at(2026, 3, 11));
        purchase(workshop, inventory, medium, AcquisitionType.NATIONAL,
                10, "50.00", at(2026, 3, 10));
        purchase(workshop, inventory, small, AcquisitionType.NATIONAL,
                1, "10.00", at(2026, 3, 10));

        List<SupplierPerformanceProjection> ranking = topSuppliers(workshop, 5);

        assertThat(ranking).hasSize(3);
        assertThat(ranking.get(0).getSupplierId()).isEqualTo(big.getId());
        assertThat(ranking.get(0).getTotalSpend()).isEqualByComparingTo("1500.00");
        assertThat(ranking.get(0).getAcquisitionCount()).isEqualTo(2L);
        assertThat(ranking.get(0).getUnitsReceived()).isEqualTo(15L);
        assertThat(ranking.get(0).getNationalSpend()).isEqualByComparingTo("1000.00");
        assertThat(ranking.get(0).getImportationSpend()).isEqualByComparingTo("500.00");

        assertThat(ranking.get(1).getSupplierId()).isEqualTo(medium.getId());
        assertThat(ranking.get(2).getSupplierId()).isEqualTo(small.getId());
    }

    @Test
    void limitsTheRankingToFive() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);

        for (int index = 0; index < 8; index++) {
            purchase(workshop, inventory,
                    supplier(workshop, "Proveedor " + index),
                    AcquisitionType.NATIONAL,
                    index + 1, "10.00", at(2026, 3, 10));
        }

        assertThat(topSuppliers(workshop, 5)).hasSize(5);
    }

    /**
     * Empate perfecto en importe y en numero de compras: el orden lo decide el
     * nombre y, en ultimo termino, el id. Sin esa ultima clave, dos proveedores
     * homonimos podian alternarse en el corte del top entre peticiones
     * identicas.
     */
    @Test
    void breaksTiesDeterministically() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);

        Supplier alfa = supplier(workshop, "AAA Alfa");
        Supplier zeta = supplier(workshop, "ZZZ Zeta");
        purchase(workshop, inventory, zeta, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));
        purchase(workshop, inventory, alfa, AcquisitionType.NATIONAL,
                10, "100.00", at(2026, 3, 10));

        List<UUID> firstRead = topSuppliers(workshop, 5).stream()
                .map(SupplierPerformanceProjection::getSupplierId).toList();

        assertThat(firstRead).containsExactly(alfa.getId(), zeta.getId());
        // Y se repite igual: el orden no depende de como PostgreSQL lea la tabla.
        assertThat(topSuppliers(workshop, 5))
                .extracting(SupplierPerformanceProjection::getSupplierId)
                .containsExactlyElementsOf(firstRead);
    }

    @Test
    void excludesProductionLotsFromTheRanking() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                2, "100.00", at(2026, 3, 10));
        production(workshop, inventory, 100, at(2026, 3, 10));

        List<SupplierPerformanceProjection> ranking = topSuppliers(workshop, 5);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).getUnitsReceived()).isEqualTo(2L);
        assertThat(ranking.get(0).getTotalSpend()).isEqualByComparingTo("200.00");
    }

    @Test
    void neverRanksSuppliersFromAnotherWorkshop() {
        Workshop workshop = workshop();
        purchase(workshop, inventory(workshop), supplier(workshop, "Propio"),
                AcquisitionType.NATIONAL, 1, "10.00", at(2026, 3, 10));

        Workshop other = workshop();
        purchase(other, inventory(other), supplier(other, "Ajeno"),
                AcquisitionType.NATIONAL, 99, "999.00", at(2026, 3, 10));

        assertThat(topSuppliers(workshop, 5))
                .extracting(SupplierPerformanceProjection::getSupplierName)
                .allSatisfy(name -> assertThat(name).startsWith("Propio"));
    }

    /** Con rango, el ranking mide SOLO el periodo pedido. */
    @Test
    void restrictsTheRankingToTheRequestedPeriod() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier oldSupplier = supplier(workshop, "Antiguo");
        Supplier recent = supplier(workshop, "Reciente");

        purchase(workshop, inventory, oldSupplier, AcquisitionType.NATIONAL,
                100, "100.00", at(2026, 1, 5));
        purchase(workshop, inventory, recent, AcquisitionType.NATIONAL,
                1, "10.00", at(2026, 6, 5));

        List<SupplierPerformanceProjection> june = repository.findTopSuppliers(
                workshop.getId(),
                LocalDate.of(2026, 6, 1).atStartOfDay(),
                LocalDate.of(2026, 7, 1).atStartOfDay(),
                5);

        assertThat(june).hasSize(1);
        assertThat(june.get(0).getSupplierId()).isEqualTo(recent.getId());

        // Sin rango, el historico incluye los dos y el antiguo encabeza.
        List<SupplierPerformanceProjection> allTime =
                repository.findTopSuppliers(workshop.getId(), null, null, 5);
        assertThat(allTime).hasSize(2);
        assertThat(allTime.get(0).getSupplierId()).isEqualTo(oldSupplier.getId());
    }

    @Test
    void reportsTheFirstPurchaseDate() {
        Workshop workshop = workshop();
        Inventory inventory = inventory(workshop);
        Supplier supplier = supplier(workshop, "Resortes");

        assertThat(repository.findFirstPurchaseDate(workshop.getId())).isNull();

        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                1, "10.00", at(2026, 6, 5));
        purchase(workshop, inventory, supplier, AcquisitionType.NATIONAL,
                1, "10.00", at(2026, 2, 3));

        assertThat(repository.findFirstPurchaseDate(workshop.getId()))
                .isEqualTo(at(2026, 2, 3));
    }

    // ── Apoyo ────────────────────────────────────────────────────

    private List<AcquisitionBucketProjection> buckets(
            Workshop workshop, String unit, String type) {
        return buckets(workshop, unit, FROM, TO, type);
    }

    private List<AcquisitionBucketProjection> buckets(
            Workshop workshop, String unit,
            LocalDateTime from, LocalDateTime to, String type) {
        return repository.findPurchaseBuckets(
                workshop.getId(), unit, from, to, type);
    }

    private List<SupplierPerformanceProjection> topSuppliers(
            Workshop workshop, int maxRows) {
        return repository.findTopSuppliers(workshop.getId(), FROM, TO, maxRows);
    }

    private static LocalDateTime at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atTime(10, 30);
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

    private Supplier supplier(Workshop workshop, String name) {
        return entityManager.persistAndFlush(Supplier.builder()
                .workshop(workshop)
                .name(name + " " + unique())
                .build());
    }

    private Inventory inventory(Workshop workshop) {
        String code = unique();
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(code)
                .name("NORMAL " + code)
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        Product product = entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name("PRODUCTO " + code)
                .attributes(new HashMap<>())
                .build());
        return entityManager.persistAndFlush(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("500.00"))
                .cost(new BigDecimal("1.00"))
                .stock(0)
                .minStock(0)
                .build());
    }

    private InventoryAcquisition purchase(
            Workshop workshop, Inventory inventory, Supplier supplier,
            AcquisitionType type, int units, String unitCost,
            LocalDateTime acquiredAt) {
        return entityManager.persistAndFlush(InventoryAcquisition.builder()
                .workshop(workshop)
                .inventory(inventory)
                .supplier(supplier)
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(type)
                .quantityReceived(units)
                .quantityRemaining(units)
                .unitCost(new BigDecimal(unitCost))
                .acquiredAt(acquiredAt)
                .build());
    }

    private InventoryAcquisition production(
            Workshop workshop, Inventory inventory, int units,
            LocalDateTime acquiredAt) {
        return entityManager.persistAndFlush(InventoryAcquisition.builder()
                .workshop(workshop)
                .inventory(inventory)
                .origin(AcquisitionOrigin.PRODUCTION)
                .quantityReceived(units)
                .quantityRemaining(units)
                .acquiredAt(acquiredAt)
                .build());
    }

}
