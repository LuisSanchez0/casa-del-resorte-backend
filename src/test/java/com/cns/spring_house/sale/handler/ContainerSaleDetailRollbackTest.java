package com.cns.spring_house.sale.handler;

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
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Reversibilidad de un detalle CONTAINER contra PostgreSQL: agregar una maleta a
 * una venta abierta y quitarla despues tiene que dejar la base de datos
 * exactamente como estaba, leyendo las columnas reales (`inventory.stock`,
 * `inventory_item.status`, `inventory_item.container_id`) y no el grafo en
 * memoria.
 *
 * El invariante que se comprueba es el del modelo de inventario, no una
 * suposicion: una hoja dentro de una maleta ya salio del inventario individual
 * cuando la maleta se armo (ContainerInventoryHandler la deja UNAVAILABLE y
 * descuenta su stock), asi que vender la maleta solo cambia estados y quitarla
 * solo los devuelve. La unica unidad que mueve stock es la del contenedor.
 *
 * Antes, quitar el detalle sumaba stock individual a cada hoja y las dejaba en
 * SOLD: el inventario ofrecia sueltas unas unidades que seguian fisicamente
 * dentro de la maleta, y cada ciclo agregar/quitar inventaba una hoja mas.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ContainerSaleDetailRollbackTest {

    private static final String TEMPORARY_DATABASE = "container_sale_it";

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ContainerSaleDetailHandler handler;

    private Workshop workshop;
    private Inventory containerInventory;
    private Inventory componentInventory;

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
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE
                    + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        registry.add("spring.datasource.url",
                () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL. Mismo tope que ya
        // usan las demas pruebas de base de datos del proyecto.
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

    @BeforeEach
    void setUp() {
        handler = new ContainerSaleDetailHandler(itemRepository);
        workshop = entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        // El handler resuelve el item por el taller del token.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
        containerInventory = inventory("MALETA CRV", FamilyCategory.CONTAINER);
        componentInventory = inventory("HOJA CRV", FamilyCategory.SPECIAL);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Inventory inventory(String productName, FamilyCategory category) {
        String code = unique();
        Family family = entityManager.persistAndFlush(Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .build());
        Product product = entityManager.persistAndFlush(Product.builder()
                .family(family)
                .name(productName + " " + code)
                .attributes(new HashMap<>())
                .build());
        return entityManager.persistAndFlush(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .stock(0)
                .minStock(0)
                .build());
    }

    /**
     * Maleta fisica armada con `count` hojas dentro, tal y como la deja
     * ContainerInventoryHandler: la unidad del contenedor suma stock y cada hoja
     * queda UNAVAILABLE apuntando a ella, ya descontada de su propio inventario.
     */
    private InventoryItem assembledBag(int count) {
        InventoryItem bag = entityManager.persistAndFlush(InventoryItem.builder()
                .inventory(containerInventory)
                .status(InventoryItemStatus.AVAILABLE)
                .build());
        for (int index = 0; index < count; index++) {
            InventoryItem component = entityManager.persistAndFlush(
                    InventoryItem.builder()
                            .inventory(componentInventory)
                            .status(InventoryItemStatus.UNAVAILABLE)
                            .build());
            component.setContainer(bag);
        }
        containerInventory.addStock(1);
        entityManager.flush();
        return bag;
    }

    private Sale openSale() {
        Employee employee = entityManager.persistAndFlush(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        return entityManager.persistAndFlush(Sale.builder()
                .workshop(workshop)
                .employee(employee)
                .build());
    }

    /**
     * Simula la siguiente peticion HTTP: contexto limpio para que el handler
     * trabaje con entidades releidas de la base y no con un grafo en memoria que
     * en produccion nunca existe.
     */
    private void nextRequest() {
        entityManager.flush();
        entityManager.clear();
    }

    private UUID addBagToSale(UUID saleId, UUID bagId) {
        nextRequest();
        Sale sale = entityManager.find(Sale.class, saleId);
        SaleDetail detail = handler.addDetail(sale, new AddSaleDetailRequestDTO(
                containerInventory.getProduct().getId(),
                bagId, null, null, null, null));
        entityManager.flush();
        return detail.getId();
    }

    private void removeBagFromSale(UUID saleId, UUID detailId) {
        nextRequest();
        Sale sale = entityManager.find(Sale.class, saleId);
        SaleDetail detail = sale.getDetails().stream()
                .filter(candidate -> candidate.getId().equals(detailId))
                .findFirst()
                .orElseThrow();
        handler.removeDetail(sale, detail);
        entityManager.flush();
    }

    // ── Lecturas directas de la base de datos ────────────────────

    private int stockOf(Inventory inventory) {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT stock FROM inventory WHERE id = :id")
                .setParameter("id", inventory.getId())
                .getSingleResult()).intValue();
    }

    private String statusOf(UUID itemId) {
        entityManager.flush();
        return (String) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT status FROM inventory_item WHERE id = :id")
                .setParameter("id", itemId)
                .getSingleResult();
    }

    private UUID containerOf(UUID itemId) {
        entityManager.flush();
        return (UUID) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT container_id FROM inventory_item WHERE id = :id")
                .setParameter("id", itemId)
                .getSingleResult();
    }

    private List<UUID> containedIds(UUID bagId) {
        entityManager.flush();
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT id FROM inventory_item "
                        + "WHERE container_id = :id ORDER BY created_at")
                .setParameter("id", bagId)
                .getResultList();
        return ids;
    }

    private long saleDetailCount(UUID saleId) {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT COUNT(*) FROM sale_detail WHERE sale_id = :id")
                .setParameter("id", saleId)
                .getSingleResult()).longValue();
    }

    // ── Pruebas ──────────────────────────────────────────────────

    /**
     * Agregar y quitar deja la base tal y como estaba: el mismo estado, el mismo
     * stock y las mismas hojas dentro de la misma maleta.
     */
    @Test
    void addingAndRemovingAContainerLeavesTheInventoryExactlyAsItWas() {
        UUID bagId = assembledBag(4).getId();
        List<UUID> componentIds = containedIds(bagId);
        UUID saleId = openSale().getId();

        assertThat(statusOf(bagId)).isEqualTo("AVAILABLE");
        assertThat(stockOf(containerInventory)).isEqualTo(1);
        assertThat(stockOf(componentInventory)).isZero();
        assertThat(componentIds).hasSize(4);

        removeBagFromSale(saleId, addBagToSale(saleId, bagId));

        // Contenedor: vuelve a estar disponible y devuelve su unidad al stock.
        assertThat(statusOf(bagId)).isEqualTo("AVAILABLE");
        assertThat(stockOf(containerInventory)).isEqualTo(1);
        // Hojas: siguen dentro de la maleta y siguen fuera del stock individual.
        for (UUID componentId : componentIds) {
            assertThat(statusOf(componentId)).isEqualTo("UNAVAILABLE");
            assertThat(containerOf(componentId)).isEqualTo(bagId);
        }
        assertThat(stockOf(componentInventory)).isZero();
        assertThat(containedIds(bagId)).containsExactlyInAnyOrderElementsOf(componentIds);
        assertThat(saleDetailCount(saleId)).isZero();
    }

    /** Estado intermedio: con la maleta ya en la venta, nada esta disponible. */
    @Test
    void addingAContainerSellsItWithEverythingInsideIt() {
        UUID bagId = assembledBag(3).getId();
        List<UUID> componentIds = containedIds(bagId);
        UUID saleId = openSale().getId();

        addBagToSale(saleId, bagId);

        assertThat(statusOf(bagId)).isEqualTo("SOLD");
        assertThat(stockOf(containerInventory)).isZero();
        for (UUID componentId : componentIds) {
            assertThat(statusOf(componentId)).isEqualTo("SOLD");
            // Vender la maleta no desarma nada.
            assertThat(containerOf(componentId)).isEqualTo(bagId);
        }
        // Las hojas ya se habian descontado al armar la maleta: venderla no las
        // vuelve a descontar.
        assertThat(stockOf(componentInventory)).isZero();
        assertThat(saleDetailCount(saleId)).isEqualTo(1L);
    }

    /**
     * La reversibilidad no se degrada al repetirla: el ciclo antiguo sumaba una
     * hoja fantasma al inventario individual en cada vuelta.
     */
    @Test
    void repeatingTheCycleNeverInventsIndividualStock() {
        UUID bagId = assembledBag(4).getId();
        UUID saleId = openSale().getId();

        for (int round = 0; round < 3; round++) {
            removeBagFromSale(saleId, addBagToSale(saleId, bagId));

            assertThat(stockOf(componentInventory))
                    .as("las hojas siguen dentro de la maleta, no sueltas")
                    .isZero();
            assertThat(stockOf(containerInventory)).isEqualTo(1);
            assertThat(statusOf(bagId)).isEqualTo("AVAILABLE");
        }

        assertThat(containedIds(bagId)).hasSize(4);
    }

    /** Otra maleta del mismo taller no se ve afectada en ningun momento. */
    @Test
    void anotherAssembledBagIsNeverTouched() {
        UUID bagId = assembledBag(2).getId();
        UUID otherBagId = assembledBag(2).getId();
        List<UUID> otherComponentIds = containedIds(otherBagId);
        UUID saleId = openSale().getId();

        removeBagFromSale(saleId, addBagToSale(saleId, bagId));

        assertThat(statusOf(otherBagId)).isEqualTo("AVAILABLE");
        assertThat(containedIds(otherBagId))
                .containsExactlyInAnyOrderElementsOf(otherComponentIds);
        for (UUID componentId : otherComponentIds) {
            assertThat(statusOf(componentId)).isEqualTo("UNAVAILABLE");
            assertThat(containerOf(componentId)).isEqualTo(otherBagId);
        }
        assertThat(stockOf(containerInventory)).isEqualTo(2);
        assertThat(stockOf(componentInventory)).isZero();
    }

    /**
     * Una maleta suelta, sin hojas dentro, sigue siendo un caso valido: solo se
     * mueve la unidad del contenedor.
     */
    @Test
    void anEmptyContainerIsAlsoReversible() {
        UUID bagId = assembledBag(0).getId();
        UUID saleId = openSale().getId();

        UUID detailId = addBagToSale(saleId, bagId);
        assertThat(statusOf(bagId)).isEqualTo("SOLD");
        assertThat(stockOf(containerInventory)).isZero();

        removeBagFromSale(saleId, detailId);

        assertThat(statusOf(bagId)).isEqualTo("AVAILABLE");
        assertThat(stockOf(containerInventory)).isEqualTo(1);
    }

}
