package com.cns.spring_house.transformation.handler;

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
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.acquisition.repository.SaleDetailCostAllocationRepository;
import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Nombre de la pieza resultante contra PostgreSQL de verdad, leyendo
 * `product.name` con SQL en lugar del grafo en memoria.
 *
 * Existe porque el fallo no estaba en cómo se construía el Product —eso ya
 * funcionaba— sino en la consulta que decide si hay que construirlo:
 * `findTargetProduct` busca por familia + modelo + atributos, así que en cuanto
 * una transformación anterior había creado esa misma pieza con el nombre
 * automático, la siguiente la reutilizaba y el nombre escrito por el operador
 * desaparecía sin dejar rastro. Con mocks eso no se ve; hace falta la consulta
 * real sobre datos reales.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SpecialTransformationOutputNameDatabaseTest {

    private static final String TEMPORARY_DATABASE = "special_transformation_name_it";
    private static final String CUSTOM_NAME = "Hoja reforzada especial";
    private static final BigDecimal SOURCE_COST = new BigDecimal("125.50");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private InventoryAcquisitionRepository acquisitionRepository;

    @Autowired
    private SaleDetailCostAllocationRepository allocationRepository;

    private SpecialTransformationHandler handler;

    private Workshop workshop;
    private Family family;
    private Model model;
    private Inventory inputInventory;

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
        handler = new SpecialTransformationHandler(
                productRepository,
                inventoryRepository,
                itemRepository,
                workshopRepository,
                new ObjectMapper(),
                // Libro de lotes real: la pieza de entrada consume su lote y la
                // resultante nace con uno de produccion. Es parte de lo que esta
                // prueba comprueba contra PostgreSQL.
                new InventoryLotService(
                        acquisitionRepository,
                        allocationRepository,
                        Clock.systemDefaultZone()));

        workshop = entityManager.persistAndFlush(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(Jwt.withTokenValue("token")
                        .header("alg", "HS384")
                        .subject(UUID.randomUUID().toString())
                        .claim("workshop", workshop.getId().toString())
                        .build()));

        family = persistedFamily();
        model = persistedModel();
        // La entrada lleva orificio: así la pieza no genera sobrante y la
        // prueba se queda solo con el nombre de la principal.
        inputInventory = inventoryOf(
                "HOJA DE ENTRADA",
                attributes("largo", 1200, "tieneOrificio", true));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Pruebas ──────────────────────────────────────────────────

    @Test
    void withoutACustomNameThePersistedProductKeepsTheGeneratedName() {
        transform(availableItem(), Map.of("largo", 900), null);

        assertThat(persistedNames(attributes("largo", 900, "tieneOrificio", true)))
                .containsExactly(family.getName() + " 900");
    }

    @Test
    void aCustomNameIsWhatEndsUpStoredInTheProductRow() {
        transform(availableItem(), Map.of("largo", 900), CUSTOM_NAME);

        assertThat(persistedNames(attributes("largo", 900, "tieneOrificio", true)))
                .containsExactly(CUSTOM_NAME);
    }

    /**
     * El fallo tal y como se reprodujo: primero una transformación automática,
     * que deja la pieza llamada "<familia> <largo>", y después la misma
     * transformación con nombre personalizado. Antes, la segunda reutilizaba la
     * pieza de la primera y el operador veía su nombre ignorado.
     */
    @Test
    void aCustomNameSurvivesAnEarlierAutomaticTransformationOfTheSamePiece() {
        transform(availableItem(), Map.of("largo", 900), null);
        transform(availableItem(), Map.of("largo", 900), CUSTOM_NAME);

        // Las dos piezas conviven: la automática conserva su nombre y la nueva
        // nace con el que se pidió. Ninguna se renombra.
        assertThat(persistedNames(attributes("largo", 900, "tieneOrificio", true)))
                .containsExactlyInAnyOrder(family.getName() + " 900", CUSTOM_NAME);
    }

    /** Y la unidad resultante apunta al producto con el nombre pedido. */
    @Test
    void theResultingUnitBelongsToTheProductNamedByTheOperator() {
        transform(availableItem(), Map.of("largo", 900), null);
        InventoryItem output = transform(availableItem(), Map.of("largo", 900), CUSTOM_NAME);

        assertThat(productNameOf(output.getId())).isEqualTo(CUSTOM_NAME);
    }

    /** Pedir dos veces el mismo nombre no duplica el catálogo. */
    @Test
    void askingTwiceForTheSameNameReusesTheSameProduct() {
        transform(availableItem(), Map.of("largo", 900), CUSTOM_NAME);
        transform(availableItem(), Map.of("largo", 900), CUSTOM_NAME);

        assertThat(persistedNames(attributes("largo", 900, "tieneOrificio", true)))
                .containsExactly(CUSTOM_NAME);
    }

    /**
     * Con dos piezas equivalentes conviviendo, la transformación automática
     * siguiente no puede reventar: la consulta devuelve una sola fila.
     */
    @Test
    void anAutomaticTransformationStillWorksWithEquivalentProductsAround() {
        transform(availableItem(), Map.of("largo", 900), null);
        transform(availableItem(), Map.of("largo", 900), CUSTOM_NAME);

        InventoryItem output = transform(availableItem(), Map.of("largo", 900), null);

        assertThat(productNameOf(output.getId())).isEqualTo(family.getName() + " 900");
    }

    // ── Auxiliares ───────────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Family persistedFamily() {
        // `family.name` es UNIQUE y las migraciones ya siembran familias reales:
        // la de la prueba se distingue para no chocar con ellas.
        Family persisted = entityManager.persistAndFlush(Family.builder()
                .code(unique())
                .name("HOJA DE RESORTE " + unique())
                .requiresModel(true)
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>())
                .build());
        persisted.addAttribute(attribute(persisted, "largo", AttributeDataType.NUMBER));
        persisted.addAttribute(
                attribute(persisted, "tieneOrificio", AttributeDataType.BOOLEAN));
        return entityManager.persistAndFlush(persisted);
    }

    private Attribute attribute(Family owner, String name, AttributeDataType dataType) {
        return Attribute.builder()
                .family(owner)
                .name(name)
                .dataType(dataType)
                .isRequired(false)
                .build();
    }

    private Model persistedModel() {
        Brand brand = entityManager.persistAndFlush(Brand.builder()
                .name("MARCA " + unique())
                .logoKey("brands/" + unique() + ".png")
                .build());
        return entityManager.persistAndFlush(Model.builder()
                .brand(brand)
                .name("MODELO " + unique())
                .yearFrom(2020)
                .yearTo(2020)
                .build());
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    private Inventory inventoryOf(String productName, Map<String, Object> productAttributes) {
        Product product = entityManager.persistAndFlush(Product.builder()
                .family(family)
                .model(model)
                .name(productName)
                .sku("SKU-" + unique())
                .attributes(productAttributes)
                .build());
        return entityManager.persistAndFlush(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("400.00"))
                .cost(SOURCE_COST)
                .stock(0)
                .minStock(0)
                .build());
    }

    /** Una unidad física disponible más en el inventario de entrada. */
    private InventoryItem availableItem() {
        InventoryItem item = entityManager.persistAndFlush(InventoryItem.builder()
                .inventory(inputInventory)
                .status(InventoryItemStatus.AVAILABLE)
                .build());
        inputInventory.addStock(1);
        // Toda existencia necesita su lote: sin el, consumir la pieza de entrada
        // se quedaria sin lote que descontar.
        entityManager.persistAndFlush(InventoryAcquisition.builder()
                .workshop(workshop)
                .inventory(inputInventory)
                .origin(AcquisitionOrigin.PRODUCTION)
                .quantityReceived(1)
                .quantityRemaining(1)
                .acquiredAt(LocalDateTime.now())
                .build());
        entityManager.flush();
        return item;
    }

    /** Ejecuta la transformación y devuelve la unidad resultante. */
    private InventoryItem transform(
            InventoryItem inputItem,
            Map<String, Object> outputAttributes,
            String outputName) {
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem, new CreateTransformationRequestDTO(
                inputItem.getId(), null, null, null,
                outputAttributes, null, null, outputName));
        entityManager.flush();
        return transformation.getItems()
                .stream()
                .filter(item -> item.getRole() == TransformationItemRole.OUTPUT)
                .map(TransformationItem::getItem)
                .findFirst()
                .orElseThrow();
    }

    // ── Lecturas directas de la base de datos ────────────────────

    /** Nombres guardados para esa configuración exacta de características. */
    private List<String> persistedNames(Map<String, Object> productAttributes) {
        entityManager.flush();
        @SuppressWarnings("unchecked")
        List<String> names = entityManager.getEntityManager()
                .createNativeQuery("""
                    SELECT name FROM product
                    WHERE family_id = :family
                        AND model_id = :model
                        AND attributes = cast(:attributes as jsonb)
                    ORDER BY created_at
                    """)
                .setParameter("family", family.getId())
                .setParameter("model", model.getId())
                .setParameter("attributes",
                        new ObjectMapper().writeValueAsString(productAttributes))
                .getResultList();
        return names;
    }

    private String productNameOf(UUID itemId) {
        entityManager.flush();
        return (String) entityManager.getEntityManager()
                .createNativeQuery("""
                    SELECT p.name
                    FROM inventory_item ii
                        JOIN inventory i ON i.id = ii.inventory_id
                        JOIN product p ON p.id = i.product_id
                    WHERE ii.id = :id
                    """)
                .setParameter("id", itemId)
                .getSingleResult();
    }

}
