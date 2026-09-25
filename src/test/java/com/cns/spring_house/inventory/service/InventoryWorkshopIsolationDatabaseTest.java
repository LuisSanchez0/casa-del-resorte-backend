package com.cns.spring_house.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.dto.InventoryResponseDTO;
import com.cns.spring_house.inventory.dto.SearchInventoryRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El inventario que ve cada taller, contra PostgreSQL de verdad.
 *
 * Lo que se comprueba aqui NO es un filtro: es el limite entre dos negocios. El
 * alcance por taller era un parametro de consulta —`SearchInventoryRequestDTO`
 * declaraba `workshop` y `InventoryServiceImpl` lo usaba tal cual—, de modo que
 * quien preguntaba elegia que datos recibia:
 *
 * <ul>
 * <li>Sin `workshop`, la especificacion no acotaba nada y la respuesta traia el
 * inventario de TODOS los talleres, con sus costos y sus precios.</li>
 * <li>Con el UUID de otro taller, traia el de ese otro.</li>
 * </ul>
 *
 * Ahora el taller sale del token y el campo ya no existe en el DTO, asi que no
 * hay nada que omitir ni que falsear. Estas pruebas fijan las dos mitades: que
 * cada taller sigue viendo lo suyo, y que ninguna forma de preguntar alcanza lo
 * ajeno.
 *
 * Se prueba contra el SERVICIO y no contra la especificacion suelta a proposito:
 * el fallo estaba en quien decidia el alcance, no en como se escribia el WHERE.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class InventoryWorkshopIsolationDatabaseTest {

    private static final String TEMPORARY_DATABASE = "inventory_isolation_it";

    @Autowired
    private InventoryService service;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Workshop own;
    private Workshop foreign;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y el
        // TestContext los CACHEA hasta que acaba la JVM: sin acotar el pool, la
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Datos de partida ─────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Dos talleres con inventario propio y un producto COMPARTIDO del catalogo.
     *
     * El producto compartido no es decorativo: el catalogo es global y lo normal
     * es que dos talleres manejen la misma pieza con precios y existencias
     * distintos. Es el caso en el que un filtro por producto sin acotar por
     * taller devolveria las dos filas.
     */
    @BeforeEach
    void seedTwoWorkshops() {
        own = workshop("PROPIO");
        foreign = workshop("AJENO");

        Product shared = product("PIEZA COMPARTIDA");

        // Propio: la pieza compartida y una suya.
        inventory(own, shared, new BigDecimal("100.00"), new BigDecimal("40.00"), 10, 2);
        inventory(own, product("PIEZA PROPIA"), new BigDecimal("55.00"),
                new BigDecimal("20.00"), 1, 5);

        // Ajeno: la MISMA pieza compartida, con otro precio y otras existencias.
        inventory(foreign, shared, new BigDecimal("777.00"), new BigDecimal("300.00"), 99, 1);
        inventory(foreign, product("PIEZA AJENA"), new BigDecimal("888.00"),
                new BigDecimal("400.00"), 50, 1);

        authenticateAgainst(own);
    }

    private Workshop workshop(String name) {
        return workshopRepository.save(Workshop.builder()
                .name(name + " " + unique())
                .address("DIRECCION")
                .build());
    }

    private Product product(String name) {
        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name("FAMILIA " + code)
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        return productRepository.save(Product.builder()
                .family(family)
                .name(name + " " + code)
                .attributes(new HashMap<>())
                .build());
    }

    private Inventory inventory(
            Workshop workshop, Product product,
            BigDecimal price, BigDecimal cost, int stock, int minStock) {
        return inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(price)
                .cost(cost)
                .stock(stock)
                .minStock(minStock)
                .build());
    }

    /**
     * El taller viaja en la claim `workshop`, que es de donde lo lee
     * `SecurityUtils`. No hace falta una cuenta real: la busqueda de inventario
     * solo consulta el taller del token.
     */
    private void authenticateAgainst(Workshop workshop) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** Todos los filtros vacios: la busqueda que hace la caja al abrirse. */
    private static SearchInventoryRequestDTO noFilters() {
        return new SearchInventoryRequestDTO(null, null, null, null, null, null);
    }

    private List<UUID> workshopsIn(List<InventoryResponseDTO> found) {
        return found.stream().map(row -> row.workshop().id()).distinct().toList();
    }

    // ── Alcance ──────────────────────────────────────────────────

    /**
     * Sin ningun filtro, la respuesta es el inventario del taller autenticado y
     * solo ese. Antes esta misma llamada devolvia el de todos.
     */
    @Test
    void withoutAnyFilterOnlyTheAuthenticatedWorkshopIsReturned() {
        List<InventoryResponseDTO> found = service.getAll(noFilters());

        assertThat(found).hasSize(2);
        assertThat(workshopsIn(found)).containsExactly(own.getId());
    }

    /** Y el otro taller ve lo suyo: el alcance sigue a quien pregunta. */
    @Test
    void eachWorkshopSeesItsOwnInventory() {
        authenticateAgainst(foreign);

        List<InventoryResponseDTO> found = service.getAll(noFilters());

        assertThat(found).hasSize(2);
        assertThat(workshopsIn(found)).containsExactly(foreign.getId());
    }

    /**
     * El precio del taller ajeno no se filtra por casualidad: la pieza
     * COMPARTIDA existe en los dos, y lo que vuelve es la fila propia con su
     * precio y sus existencias, no la ajena.
     */
    @Test
    void aSharedProductReturnsOnlyTheRowOfTheAuthenticatedWorkshop() {
        List<InventoryResponseDTO> found = service.getAll(noFilters());

        assertThat(found)
                .filteredOn(row -> row.product().name().startsWith("PIEZA COMPARTIDA"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.price()).isEqualByComparingTo("100.00");
                    assertThat(row.stock()).isEqualTo(10);
                    assertThat(row.workshop().id()).isEqualTo(own.getId());
                });
    }

    // ── Los demas filtros siguen acotados ────────────────────────

    /**
     * Filtrar por el PRODUCTO compartido no puede traer la fila del otro taller.
     * Es el caso que mas facilmente se colaba: el producto es el mismo id.
     */
    @Test
    void theProductFilterStaysScopedToTheAuthenticatedWorkshop() {
        UUID sharedProduct = service.getAll(noFilters()).stream()
                .filter(row -> row.product().name().startsWith("PIEZA COMPARTIDA"))
                .findFirst()
                .orElseThrow()
                .product()
                .id();

        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(
                        sharedProduct, null, null, null, null, null));

        assertThat(found).singleElement().satisfies(row -> {
            assertThat(row.workshop().id()).isEqualTo(own.getId());
            assertThat(row.price()).isEqualByComparingTo("100.00");
        });
    }

    /**
     * Un rango de precio que SOLO cubre importes del taller ajeno no devuelve
     * nada. Sin el alcance, esta consulta era una forma directa de leer los
     * precios de otro negocio.
     */
    @Test
    void thePriceFilterCannotReachAnotherWorkshop() {
        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(
                        null, null, null,
                        new BigDecimal("700.00"), new BigDecimal("900.00"), null));

        assertThat(found).isEmpty();
    }

    /** El mismo rango, aplicado por su dueño, si devuelve sus dos filas caras. */
    @Test
    void thePriceFilterStillWorksInsideTheOwnWorkshop() {
        authenticateAgainst(foreign);

        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(
                        null, null, null,
                        new BigDecimal("700.00"), new BigDecimal("900.00"), null));

        assertThat(found).hasSize(2);
        assertThat(workshopsIn(found)).containsExactly(foreign.getId());
    }

    @Test
    void theCostFilterCannotReachAnotherWorkshop() {
        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(
                        null, new BigDecimal("250.00"), new BigDecimal("500.00"),
                        null, null, null));

        assertThat(found).isEmpty();
    }

    /**
     * El filtro de stock bajo tambien queda acotado: el taller ajeno tiene una
     * fila por debajo de su minimo y no puede asomar aqui.
     */
    @Test
    void theLowStockFilterStaysScopedToTheAuthenticatedWorkshop() {
        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(null, null, null, null, null, true));

        assertThat(found).isNotEmpty();
        assertThat(workshopsIn(found)).containsExactly(own.getId());
        // La propia con stock 1 y minimo 5 es la que esta baja.
        assertThat(found).singleElement().satisfies(row ->
                assertThat(row.product().name()).startsWith("PIEZA PROPIA"));
    }

    /**
     * `isLowStock=false` es el complemento del anterior y tambien queda acotado:
     * un filtro negado no puede convertirse en una puerta al resto de talleres.
     */
    @Test
    void theNegatedLowStockFilterStaysScopedToo() {
        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(null, null, null, null, null, false));

        assertThat(found).isNotEmpty();
        assertThat(workshopsIn(found)).containsExactly(own.getId());
    }

    /**
     * Combinar filtros tampoco ensancha el alcance: el taller se aplica SIEMPRE,
     * y los demas criterios se suman, nunca lo sustituyen.
     */
    @Test
    void combiningFiltersNeverWidensTheScope() {
        List<InventoryResponseDTO> found = service.getAll(
                new SearchInventoryRequestDTO(
                        null,
                        new BigDecimal("0.00"), new BigDecimal("100000.00"),
                        new BigDecimal("0.00"), new BigDecimal("100000.00"),
                        null));

        assertThat(found).hasSize(2);
        assertThat(workshopsIn(found)).containsExactly(own.getId());
    }
}
