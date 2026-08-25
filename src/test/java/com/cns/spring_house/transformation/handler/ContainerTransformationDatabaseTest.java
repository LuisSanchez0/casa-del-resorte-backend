package com.cns.spring_house.transformation.handler;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Desarmado de una maleta real contra PostgreSQL: cada liberación se comprueba
 * leyendo las columnas (`inventory.stock`, `inventory_item.status`,
 * `inventory_item.container_id`) con SQL, no con el grafo en memoria.
 *
 * Es la versión automatizada de la verificación manual en base de datos: si
 * alguien vuelve a descontar el stock del contenedor en una liberación parcial,
 * esta prueba lo detecta con los datos reales.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ContainerTransformationDatabaseTest {

    private static final String TEMPORARY_DATABASE = "container_transformation_it";

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ContainerTransformationHandler handler;

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
        handler = new ContainerTransformationHandler(itemRepository);
        workshop = entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        containerInventory = inventory("MALETA CRV", FamilyCategory.CONTAINER, 0);
        componentInventory = inventory("HOJA CRV", FamilyCategory.SPECIAL, 0);
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Inventory inventory(
            String productName, FamilyCategory category, int stock) {
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
                .stock(stock)
                .minStock(0)
                .build());
    }

    private InventoryItem persistedItem(
            Inventory inventory, InventoryItemStatus status) {
        return entityManager.persistAndFlush(InventoryItem.builder()
                .inventory(inventory)
                .status(status)
                .build());
    }

    /**
     * Maleta física armada con `count` hojas dentro, tal y como la deja
     * ContainerInventoryHandler: la unidad del contenedor suma stock y cada hoja
     * queda UNAVAILABLE apuntando a ella.
     */
    private InventoryItem assembledBag(int count) {
        InventoryItem bag =
                persistedItem(containerInventory, InventoryItemStatus.AVAILABLE);
        for (int index = 0; index < count; index++) {
            InventoryItem component =
                    persistedItem(componentInventory, InventoryItemStatus.UNAVAILABLE);
            component.setContainer(bag);
        }
        containerInventory.addStock(1);
        entityManager.flush();
        return bag;
    }

    /** Simula la siguiente petición HTTP: contexto limpio y entidad releída. */
    private InventoryItem reload(UUID itemId) {
        entityManager.flush();
        entityManager.clear();
        return itemRepository.findById(itemId).orElseThrow();
    }

    private void release(InventoryItem bag, List<UUID> components) {
        handler.generate(
                Transformation.builder().build(), bag,
                new CreateTransformationRequestDTO(
                        bag.getId(), null, null, components, null, null, null, null));
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

    private long containedCount(UUID bagId) {
        entityManager.flush();
        return ((Number) entityManager.getEntityManager()
                .createNativeQuery(
                        "SELECT COUNT(*) FROM inventory_item "
                        + "WHERE container_id = :id")
                .setParameter("id", bagId)
                .getSingleResult()).longValue();
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

    // ── Pruebas ──────────────────────────────────────────────────

    /**
     * Recorrido completo de la verificación manual: dos maletas en stock, una de
     * ellas con 4 hojas, liberadas de una en una.
     */
    @Test
    void releasingComponentsOneByOneOnlyDiscountsStockWithTheLastOne() {
        InventoryItem bag = assembledBag(4);
        InventoryItem otherBag = assembledBag(2);
        UUID bagId = bag.getId();
        List<UUID> componentIds = new ArrayList<>(containedIds(bagId));

        assertThat(stockOf(containerInventory)).isEqualTo(2);
        assertThat(componentIds).hasSize(4);

        for (int index = 0; index < 3; index++) {
            UUID componentId = componentIds.get(index);

            release(reload(bagId), List.of(componentId));

            // La hoja sale de la maleta y vuelve al inventario suelta.
            assertThat(statusOf(componentId)).isEqualTo("AVAILABLE");
            assertThat(containerOf(componentId)).isNull();
            assertThat(stockOf(componentInventory)).isEqualTo(index + 1);
            // La maleta física sigue ahí, con el resto dentro y sin tocar stock.
            assertThat(containedCount(bagId)).isEqualTo(3L - index);
            assertThat(statusOf(bagId)).isEqualTo("UNAVAILABLE");
            assertThat(stockOf(containerInventory)).isEqualTo(2);
        }

        // Las hojas que no se seleccionaron siguen apuntando a la misma maleta.
        assertThat(containerOf(componentIds.get(3))).isEqualTo(bagId);

        release(reload(bagId), List.of(componentIds.get(3)));

        assertThat(containedCount(bagId)).isZero();
        assertThat(statusOf(bagId)).isEqualTo("CONSUMED");
        assertThat(stockOf(containerInventory)).isEqualTo(1);
        assertThat(stockOf(componentInventory)).isEqualTo(4);
        // La otra maleta física no se ve afectada en ningún momento.
        assertThat(statusOf(otherBag.getId())).isEqualTo("AVAILABLE");
        assertThat(containedCount(otherBag.getId())).isEqualTo(2L);
    }

    /** "Liberar toda la maleta": un solo descuento, nunca dos. */
    @Test
    void releasingTheWholeBagInOneRequestDiscountsStockExactlyOnce() {
        InventoryItem bag = assembledBag(4);
        assembledBag(4);
        UUID bagId = bag.getId();

        assertThat(stockOf(containerInventory)).isEqualTo(2);

        release(reload(bagId), null);

        assertThat(containedCount(bagId)).isZero();
        assertThat(statusOf(bagId)).isEqualTo("CONSUMED");
        assertThat(stockOf(containerInventory)).isEqualTo(1);
        assertThat(stockOf(componentInventory)).isEqualTo(4);
    }

    /**
     * Una hoja de otra maleta no puede liberarse desde esta: la operación se
     * rechaza entera, sin dejar nada a medias en la base de datos.
     */
    @Test
    void aComponentFromAnotherBagChangesNothing() {
        InventoryItem bag = assembledBag(4);
        InventoryItem otherBag = assembledBag(4);
        UUID bagId = bag.getId();
        UUID foreignId = containedIds(otherBag.getId()).get(0);

        InventoryItem reloaded = reload(bagId);
        assertThatThrownBy(() -> release(reloaded, List.of(foreignId)))
                .isInstanceOf(ValidationException.class);

        assertThat(stockOf(containerInventory)).isEqualTo(2);
        assertThat(stockOf(componentInventory)).isZero();
        assertThat(containedCount(bagId)).isEqualTo(4L);
        assertThat(containedCount(otherBag.getId())).isEqualTo(4L);
        assertThat(statusOf(bagId)).isEqualTo("AVAILABLE");
        assertThat(statusOf(foreignId)).isEqualTo("UNAVAILABLE");
        assertThat(containerOf(foreignId)).isEqualTo(otherBag.getId());
    }

    /** El mismo id repetido libera una sola hoja y no vacía la maleta. */
    @Test
    void aDuplicatedComponentIdDoesNotDoubleAnyStock() {
        InventoryItem bag = assembledBag(4);
        UUID bagId = bag.getId();
        UUID componentId = containedIds(bagId).get(0);

        release(reload(bagId), List.of(componentId, componentId));

        assertThat(stockOf(componentInventory)).isEqualTo(1);
        assertThat(stockOf(containerInventory)).isEqualTo(1);
        assertThat(containedCount(bagId)).isEqualTo(3L);
        assertThat(statusOf(bagId)).isEqualTo("UNAVAILABLE");
    }

}