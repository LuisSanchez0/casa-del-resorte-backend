package com.cns.spring_house.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.hibernate.resource.jdbc.spi.StatementInspector;
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
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * `processableUnits`: cuantas unidades FISICAS de un inventario pueden todavia
 * operarse, contra PostgreSQL de verdad.
 *
 * Existe porque `stock` no responde esa pregunta. `stock` cuenta existencia
 * vendible y se mueve con sus propias reglas: un inventario puede conservar su
 * fila con todas sus unidades consumidas, y una maleta a medio desarmar deja de
 * ser vendible pero sigue pudiendo liberarse. La pantalla de transformaciones
 * necesita lo segundo, no lo primero.
 *
 * El predicado replica el del servidor al aceptar una entrada
 * (`TransformationServiceImpl.isProcessable`): disponible, o contenedor a medio
 * desarmar que aun guarda componentes.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class InventoryProcessableUnitsDatabaseTest {

    private static final String TEMPORARY_DATABASE = "inventory_processable_it";

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    public static class RecordingInspector implements StatementInspector {

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }

    }

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

    @Autowired
    private InventoryItemRepository itemRepository;

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add(
                "spring.jpa.properties.hibernate.session_factory.statement_inspector",
                RecordingInspector.class::getName);
    }

    @BeforeEach
    void setUp() {
        own = workshop("TALLER PROPIO");
        foreign = workshop("TALLER AJENO");
        authenticateIn(own);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Workshop workshop(String name) {
        return workshopRepository.save(Workshop.builder()
                .name(name + " " + unique())
                .address("DIRECCION")
                .build());
    }

    private void authenticateIn(Workshop target) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", target.getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Inventory inventory(Workshop target, FamilyCategory category, int stock) {
        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .build());
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name("PIEZA " + code)
                .attributes(new HashMap<>())
                .build());
        return inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(target)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .stock(stock)
                .minStock(0)
                .build());
    }

    private InventoryItem item(Inventory inventory, InventoryItemStatus status) {
        return itemRepository.save(InventoryItem.builder()
                .inventory(inventory)
                .status(status)
                .build());
    }

    private static SearchInventoryRequestDTO noFilters() {
        return new SearchInventoryRequestDTO(null, null, null, null, null, null);
    }

    private int processableOf(UUID inventoryId) {
        return service.getAll(noFilters()).stream()
                .filter(row -> row.id().equals(inventoryId))
                .map(InventoryResponseDTO::processableUnits)
                .findFirst()
                .orElseThrow(() -> new AssertionError("El inventario no aparece en la lista"));
    }

    // ── A. Con una unidad disponible, cuenta ─────────────────────

    @Test
    void unaPiezaEspecialDisponibleCuentaComoOperable() {
        Inventory special = inventory(own, FamilyCategory.SPECIAL, 1);
        item(special, InventoryItemStatus.AVAILABLE);

        assertThat(processableOf(special.getId())).isEqualTo(1);
    }

    // ── B. Sin unidades operables, cuenta cero ───────────────────

    @Test
    void unInventarioSinUnidadesOperablesCuentaCero() {
        Inventory special = inventory(own, FamilyCategory.SPECIAL, 0);
        item(special, InventoryItemStatus.CONSUMED);
        item(special, InventoryItemStatus.SOLD);

        assertThat(processableOf(special.getId())).isZero();
    }

    /**
     * El caso que motiva todo esto: la fila de inventario sigue existiendo —con
     * su producto, su precio y su costo— pero no queda ni una unidad fisica.
     */
    @Test
    void unInventarioSinNingunaUnidadCuentaCero() {
        Inventory special = inventory(own, FamilyCategory.SPECIAL, 0);

        assertThat(processableOf(special.getId())).isZero();
    }

    /**
     * `stock` y las unidades operables son cosas distintas: aqui el contador
     * dice 5 y no hay ni una unidad sobre la que actuar. Por eso la pantalla no
     * puede fiarse de `stock`.
     */
    @Test
    void elContadorDeExistenciasNoDecideLoQueSePuedeOperar() {
        Inventory special = inventory(own, FamilyCategory.SPECIAL, 5);
        item(special, InventoryItemStatus.CONSUMED);

        InventoryResponseDTO row = service.getAll(noFilters()).stream()
                .filter(candidate -> candidate.id().equals(special.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(row.stock()).isEqualTo(5);
        assertThat(row.processableUnits()).isZero();
    }

    // ── C. El taller ajeno no suma ───────────────────────────────

    @Test
    void lasUnidadesDeOtroTallerNoCuentan() {
        Inventory foreignInventory = inventory(foreign, FamilyCategory.SPECIAL, 3);
        item(foreignInventory, InventoryItemStatus.AVAILABLE);
        item(foreignInventory, InventoryItemStatus.AVAILABLE);

        // Ni aparece en la lista del taller autenticado.
        assertThat(service.getAll(noFilters()))
                .noneMatch(row -> row.id().equals(foreignInventory.getId()));

        // Y desde su propio taller si se ven.
        authenticateIn(foreign);
        assertThat(processableOf(foreignInventory.getId())).isEqualTo(2);
    }

    // ── D. La categoria no la decide este contador ───────────────

    /**
     * `processableUnits` responde "cuantas unidades pueden operarse", no "es
     * transformable": eso lo decide la categoria de la familia, que es una regla
     * aparte y anterior. Un producto NORMAL con existencias cuenta sus unidades
     * con honestidad; quien lo excluye del flujo de transformacion es
     * `isTransformableCategory`, no este numero.
     */
    @Test
    void unProductoNormalCuentaSusUnidadesSinVolverseTransformable() {
        Inventory normal = inventory(own, FamilyCategory.NORMAL, 2);
        item(normal, InventoryItemStatus.AVAILABLE);

        InventoryResponseDTO row = service.getAll(noFilters()).stream()
                .filter(candidate -> candidate.id().equals(normal.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(row.processableUnits()).isEqualTo(1);
        assertThat(row.product().family().category()).isEqualTo(FamilyCategory.NORMAL);
    }

    // ── E. Contenedor a medio desarmar ───────────────────────────

    @Test
    void unaMaletaAMedioDesarmarSigueSiendoOperable() {
        Inventory container = inventory(own, FamilyCategory.CONTAINER, 1);
        Inventory component = inventory(own, FamilyCategory.SPECIAL, 0);

        InventoryItem bag = item(container, InventoryItemStatus.UNAVAILABLE);
        InventoryItem inside = item(component, InventoryItemStatus.UNAVAILABLE);
        bag.addItem(inside);
        itemRepository.save(inside);

        assertThat(processableOf(container.getId())).isEqualTo(1);
        // El componente encerrado NO es operable por si mismo.
        assertThat(processableOf(component.getId())).isZero();
    }

    @Test
    void unaMaletaVaciaYNoDisponibleYaNoEsOperable() {
        Inventory container = inventory(own, FamilyCategory.CONTAINER, 1);
        item(container, InventoryItemStatus.UNAVAILABLE);

        assertThat(processableOf(container.getId())).isZero();
    }

    /** Una pieza suelta NO contenedora tampoco: UNAVAILABLE solo salva maletas. */
    @Test
    void unaPiezaEspecialNoDisponibleNoEsOperable() {
        Inventory special = inventory(own, FamilyCategory.SPECIAL, 1);
        item(special, InventoryItemStatus.UNAVAILABLE);

        assertThat(processableOf(special.getId())).isZero();
    }

    // ── F. Las transiciones de estado se reflejan ────────────────

    @Test
    void consumirLaUltimaUnidadDejaElInventarioEnCero() {
        Inventory special = inventory(own, FamilyCategory.SPECIAL, 1);
        InventoryItem only = item(special, InventoryItemStatus.AVAILABLE);

        assertThat(processableOf(special.getId())).isEqualTo(1);

        only.setStatus(InventoryItemStatus.CONSUMED);
        itemRepository.save(only);

        assertThat(processableOf(special.getId())).isZero();
    }

    @Test
    void liberarUnComponenteLoVuelveOperable() {
        Inventory container = inventory(own, FamilyCategory.CONTAINER, 1);
        Inventory component = inventory(own, FamilyCategory.SPECIAL, 0);

        InventoryItem bag = item(container, InventoryItemStatus.AVAILABLE);
        InventoryItem inside = item(component, InventoryItemStatus.UNAVAILABLE);
        bag.addItem(inside);
        itemRepository.save(inside);

        assertThat(processableOf(component.getId())).isZero();

        // Se suelta, como hace el desarmado.
        bag.releaseItem(inside);
        inside.setStatus(InventoryItemStatus.AVAILABLE);
        itemRepository.save(inside);

        assertThat(processableOf(component.getId())).isEqualTo(1);
    }

    // ── G. Una sola consulta, pase lo que pase ───────────────────

    /**
     * El contador se resuelve con UN agregado para todo el taller. Si alguien lo
     * convierte en una consulta por inventario, esta prueba lo detecta: con seis
     * inventarios el numero de lecturas de `inventory_item` sigue siendo uno.
     */
    @Test
    void elContadorNoIntroduceUnaConsultaPorInventario() {
        for (int index = 0; index < 6; index++) {
            Inventory row = inventory(own, FamilyCategory.SPECIAL, 1);
            item(row, InventoryItemStatus.AVAILABLE);
        }

        STATEMENTS.clear();
        List<InventoryResponseDTO> all = service.getAll(noFilters());

        assertThat(all).hasSizeGreaterThanOrEqualTo(6);
        assertThat(readsOf("inventory_item")).isEqualTo(1);
    }

    private long readsOf(String table) {
        Pattern pattern = Pattern.compile("from " + table + "\\b");
        return STATEMENTS.stream()
                .filter(sql -> sql.startsWith("select"))
                .filter(sql -> pattern.matcher(sql).find())
                .count();
    }
}
