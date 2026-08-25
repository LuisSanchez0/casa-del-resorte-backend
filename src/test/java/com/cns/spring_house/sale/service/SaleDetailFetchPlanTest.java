package com.cns.spring_house.sale.service;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El plan de carga de la rama item/inventario/producto de una venta.
 *
 * Es la otra mitad del problema que dejo ver la revision de `workResult`: aquel
 * ya no consulta por linea, pero pintar la respuesta seguia bajando por
 * `item -> inventory -> product` y por `containedItems` de cada unidad, y cada
 * uno de esos pasos es perezoso.
 *
 * Aqui cada linea lleva su PROPIO producto, inventario y unidad. No es un
 * detalle del montaje: reutilizar un solo inventario para las cincuenta lineas
 * —como hacia la prueba de `workResult`— hace que el contexto de persistencia
 * resuelva las cuarenta y nueve repeticiones sin tocar la base, y el N+1 de esa
 * rama queda escondido detras de la cache de primer nivel. Una factura real
 * vende piezas distintas.
 *
 * Se cuentan las sentencias por TABLA PRINCIPAL (`FROM x`), no las que la
 * mencionan: una tabla traida con JOIN dentro de la consulta de la venta no es
 * un viaje aparte, y contarla como tal daria por roto justo lo que se arreglo.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleDetailFetchPlanTest {

    private static final String TEMPORARY_DATABASE = "sale_detail_fetch_it";

    /**
     * Margen para diferencias de plan que NO son N+1. Una consulta suelta de mas
     * entre una venta de una linea y otra de cincuenta es ruido; cuarenta y nueve
     * son un viaje por linea.
     */
    private static final int CONSTANT_TOLERANCE = 3;

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    /** Registrado por Hibernate: necesita constructor publico sin argumentos. */
    public static class RecordingInspector implements StatementInspector {

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }

    }

    @Autowired
    private SaleService service;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryItemRepository itemRepository;

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

        registry.add("spring.datasource.url", () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add(
                "spring.jpa.properties.hibernate.session_factory.statement_inspector",
                RecordingInspector.class::getName);
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

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Medicion por tabla ───────────────────────────────────────

    /**
     * Sentencias cuya tabla PRINCIPAL es la indicada.
     *
     * El limite de palabra importa: sin el, `from inventory` contaria tambien
     * las de `inventory_item` y las dos ramas quedarian mezcladas justo cuando
     * lo que se quiere es separarlas.
     */
    private long readsOf(String table) {
        Pattern pattern = Pattern.compile("from " + table + "\\b");
        return STATEMENTS.stream()
                .filter(sql -> sql.startsWith("select"))
                .filter(sql -> pattern.matcher(sql).find())
                .count();
    }

    private void read(UUID saleId) {
        STATEMENTS.clear();
        service.get(saleId);
    }

    // ── Datos de partida ─────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        Role seller = roleRepository.findAll().stream()
                .filter(role -> "SELLER".equals(role.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El rol SELLER no esta sembrado en la base de prueba"));
        UserAccount account = userAccountRepository.save(UserAccount.builder()
                .employee(employee(workshop))
                .role(seller)
                .username("cajero_" + unique())
                .password("no-usada-en-pruebas")
                .isActive(true)
                .build());
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
        return workshop;
    }

    private Employee employee(Workshop workshop) {
        return employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    /** Cada pieza con su propia familia y su propio producto. */
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

    private Inventory inventory(Workshop workshop, Product product) {
        return inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("100.00"))
                .cost(new BigDecimal("10.00"))
                .stock(1)
                .minStock(0)
                .build());
    }

    private InventoryItem soldItem(Inventory inventory) {
        return itemRepository.save(InventoryItem.builder()
                .inventory(inventory)
                .status(InventoryItemStatus.SOLD)
                .build());
    }

    private Sale emptyCompletedSale(Workshop workshop) {
        return Sale.builder()
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .total(BigDecimal.ZERO)
                .build();
    }

    /**
     * Venta de `lines` piezas SPECIAL, cada una con producto, familia,
     * inventario y unidad PROPIOS: el caso que de verdad hace trabajar al plan
     * de carga.
     */
    private Sale saleWithDistinctItemLines(Workshop workshop, int lines) {
        Sale sale = emptyCompletedSale(workshop);
        for (int index = 0; index < lines; index++) {
            Product product = product(FamilyCategory.SPECIAL);
            InventoryItem item = soldItem(inventory(workshop, product));
            sale.addDetail(SaleDetail.builder()
                    .product(product)
                    .item(item)
                    .quantity(1)
                    .price(new BigDecimal("380.00"))
                    .subTotal(new BigDecimal("380.00"))
                    .build());
        }
        return saleRepository.save(sale);
    }

    /** Venta de `containers` contenedores, cada uno con `children` unidades dentro. */
    private Sale saleWithContainerLines(
            Workshop workshop, int containers, int children) {
        Sale sale = emptyCompletedSale(workshop);
        for (int index = 0; index < containers; index++) {
            Product containerProduct = product(FamilyCategory.CONTAINER);
            InventoryItem container = soldItem(inventory(workshop, containerProduct));

            List<InventoryItem> contained = new ArrayList<>();
            for (int child = 0; child < children; child++) {
                InventoryItem inside = soldItem(
                        inventory(workshop, product(FamilyCategory.NORMAL)));
                inside.setContainer(container);
                contained.add(itemRepository.save(inside));
            }
            container.getContainedItems().addAll(contained);

            sale.addDetail(SaleDetail.builder()
                    .product(containerProduct)
                    .item(container)
                    .quantity(1)
                    .price(new BigDecimal("900.00"))
                    .subTotal(new BigDecimal("900.00"))
                    .build());
        }
        return saleRepository.save(sale);
    }

    // ── La invariante: la cadena to-one no crece con las lineas ──

    /**
     * La prueba que falla si `item`, `inventory` o `product` vuelven a
     * resolverse linea por linea.
     */
    @Test
    void readingASaleNeverWalksItemInventoryOrProductOncePerLine() {
        Workshop workshop = authenticatedWorkshop();
        UUID one = saleWithDistinctItemLines(workshop, 1).getId();
        UUID ten = saleWithDistinctItemLines(workshop, 10).getId();
        UUID fifty = saleWithDistinctItemLines(workshop, 50).getId();

        read(one);
        long itemsOne = readsOf("inventory_item");
        long inventoryOne = readsOf("inventory");
        long productOne = readsOf("product");
        long familyOne = readsOf("family");

        read(ten);
        long itemsTen = readsOf("inventory_item");
        long inventoryTen = readsOf("inventory");
        long productTen = readsOf("product");
        long familyTen = readsOf("family");

        read(fifty);
        long itemsFifty = readsOf("inventory_item");
        long inventoryFifty = readsOf("inventory");
        long productFifty = readsOf("product");
        long familyFifty = readsOf("family");

        assertThat(itemsTen)
                .as("lecturas de inventory_item con 10 lineas (con 1: %d)", itemsOne)
                .isLessThanOrEqualTo(itemsOne + CONSTANT_TOLERANCE);
        assertThat(itemsFifty)
                .as("lecturas de inventory_item con 50 lineas (con 1: %d)", itemsOne)
                .isLessThanOrEqualTo(itemsOne + CONSTANT_TOLERANCE);

        assertThat(inventoryTen)
                .as("lecturas de inventory con 10 lineas (con 1: %d)", inventoryOne)
                .isLessThanOrEqualTo(inventoryOne + CONSTANT_TOLERANCE);
        assertThat(inventoryFifty)
                .as("lecturas de inventory con 50 lineas (con 1: %d)", inventoryOne)
                .isLessThanOrEqualTo(inventoryOne + CONSTANT_TOLERANCE);

        assertThat(productTen)
                .as("lecturas de product con 10 lineas (con 1: %d)", productOne)
                .isLessThanOrEqualTo(productOne + CONSTANT_TOLERANCE);
        assertThat(productFifty)
                .as("lecturas de product con 50 lineas (con 1: %d)", productOne)
                .isLessThanOrEqualTo(productOne + CONSTANT_TOLERANCE);

        assertThat(familyTen)
                .as("lecturas de family con 10 lineas (con 1: %d)", familyOne)
                .isLessThanOrEqualTo(familyOne + CONSTANT_TOLERANCE);
        assertThat(familyFifty)
                .as("lecturas de family con 50 lineas (con 1: %d)", familyOne)
                .isLessThanOrEqualTo(familyOne + CONSTANT_TOLERANCE);
    }

    /** Y el total de sentencias tampoco. */
    @Test
    void theTotalNumberOfStatementsDoesNotGrowWithTheNumberOfLines() {
        Workshop workshop = authenticatedWorkshop();
        UUID one = saleWithDistinctItemLines(workshop, 1).getId();
        UUID fifty = saleWithDistinctItemLines(workshop, 50).getId();

        read(one);
        long totalOne = STATEMENTS.size();
        read(fifty);
        long totalFifty = STATEMENTS.size();

        assertThat(totalFifty)
                .as("sentencias totales con 50 lineas (con 1: %d)", totalOne)
                .isLessThanOrEqualTo(totalOne + CONSTANT_TOLERANCE);
    }

    // ── Contenedores: coleccion, se mide aparte ──────────────────

    /**
     * `containedItems` es una COLECCION, asi que no puede unirse en la misma
     * consulta que `details` sin multiplicar filas. Se vigila por separado: lo
     * que no puede pasar es que sea una consulta por contenedor.
     */
    @Test
    void containedItemsAreNotFetchedOncePerContainer() {
        Workshop workshop = authenticatedWorkshop();
        UUID one = saleWithContainerLines(workshop, 1, 3).getId();
        UUID twenty = saleWithContainerLines(workshop, 20, 3).getId();

        read(one);
        long itemsOne = readsOf("inventory_item");
        long inventoryOne = readsOf("inventory");
        long totalOne = STATEMENTS.size();

        read(twenty);
        long itemsTwenty = readsOf("inventory_item");
        long inventoryTwenty = readsOf("inventory");
        long totalTwenty = STATEMENTS.size();

        assertThat(itemsTwenty)
                .as("lecturas de inventory_item con 20 contenedores (con 1: %d)", itemsOne)
                .isLessThanOrEqualTo(itemsOne + CONSTANT_TOLERANCE);
        // El inventario de cada pieza CONTENIDA era el N+1 escondido detras de
        // la coleccion: sesenta consultas para veinte maletas de tres piezas.
        assertThat(inventoryTwenty)
                .as("lecturas de inventory con 20 contenedores x3 (con 1: %d)", inventoryOne)
                .isLessThanOrEqualTo(inventoryOne + CONSTANT_TOLERANCE);
        assertThat(totalTwenty)
                .as("sentencias totales con 20 contenedores (con 1: %d)", totalOne)
                .isLessThanOrEqualTo(totalOne + CONSTANT_TOLERANCE);
    }

    /** Y el contenido sigue llegando entero y en su sitio. */
    @Test
    void containersStillCarryTheirContents() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = saleWithContainerLines(workshop, 3, 4);

        SaleResponseDTO response = service.get(sale.getId());

        assertThat(response.details()).hasSize(3);
        assertThat(response.details())
                .allSatisfy(detail -> assertThat(detail.item().containedItems())
                        .hasSize(4));
    }

    /**
     * El listado sirve el MISMO DTO, asi que paga el mismo recorrido pero
     * multiplicado por el numero de ventas. Se vigila aparte para que optimizar
     * el detalle no deje la lista atras.
     */
    @Test
    void listingSalesDoesNotWalkItemInventoryOrProductPerLine() {
        Workshop workshop = authenticatedWorkshop();
        for (int sale = 0; sale < 5; sale++) {
            saleWithDistinctItemLines(workshop, 5);
        }

        STATEMENTS.clear();
        List<SaleResponseDTO> sales = service.getAll(new SearchSaleRequestDTO(
                null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(sales).hasSize(5);
        assertThat(sales).allSatisfy(sale -> assertThat(sale.details()).hasSize(5));
        // 25 lineas con item propio: sin plan de carga serian mas de cien viajes.
        assertThat(readsOf("inventory")).as("lecturas de inventory al listar").isZero();
        assertThat(readsOf("product")).as("lecturas de product al listar").isZero();
        assertThat(readsOf("inventory_item"))
                .as("lecturas de inventory_item al listar")
                .isLessThanOrEqualTo(CONSTANT_TOLERANCE);
    }

    // ── La respuesta no cambia ───────────────────────────────────

    @Test
    void everyLineKeepsItsOwnItemProductAndAmounts() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = saleWithDistinctItemLines(workshop, 10);

        SaleResponseDTO response = service.get(sale.getId());

        assertThat(response.details()).hasSize(10);
        assertThat(response.details())
                .extracting(SaleDetailResponseDTO::id)
                .doesNotHaveDuplicates();
        // Cada linea con SU pieza: si el plan de carga mezclara filas, estos
        // dejarian de ser diez distintos.
        assertThat(response.details())
                .extracting(detail -> detail.item().id())
                .doesNotHaveDuplicates();
        assertThat(response.details())
                .extracting(detail -> detail.product().id())
                .doesNotHaveDuplicates();

        assertThat(response.details()).allSatisfy(detail -> {
            assertThat(detail.quantity()).isEqualTo(1);
            assertThat(detail.price()).isEqualByComparingTo("380.00");
            assertThat(detail.subTotal()).isEqualByComparingTo("380.00");
            assertThat(detail.product().family().category())
                    .isEqualTo(FamilyCategory.SPECIAL);
            assertThat(detail.item().productId()).isEqualTo(detail.product().id());
            assertThat(detail.item().productName()).isEqualTo(detail.product().name());
        });
    }

    /** La venta leida coincide linea a linea con la persistida. */
    @Test
    void theResponseMatchesWhatWasPersisted() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = saleWithDistinctItemLines(workshop, 10);

        SaleResponseDTO response = service.get(sale.getId());

        assertThat(response.details())
                .extracting(SaleDetailResponseDTO::id)
                .containsExactlyInAnyOrderElementsOf(
                        sale.getDetails().stream().map(SaleDetail::getId).toList());
        assertThat(response.details())
                .extracting(detail -> detail.item().id())
                .containsExactlyInAnyOrderElementsOf(
                        sale.getDetails().stream()
                                .map(detail -> detail.getItem().getId())
                                .toList());
    }

}
