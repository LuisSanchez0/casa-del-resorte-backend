package com.cns.spring_house.transformation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
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
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.SearchTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El limite entre dos talleres en `/v1/transformations`, contra PostgreSQL.
 *
 * El defecto que fija este fichero: `TransformationServiceImpl.create` cargaba
 * la unidad de entrada con `itemRepository.findById(...)`, SIN acotar por
 * taller. Conocer —o acertar— un UUID de `inventory_item` de otro taller
 * bastaba para transformarlo, y el dano no era solo de lectura: el manejador
 * SPECIAL hace `inputItem.getInventory().removeStock(1)` y consume su lote, y
 * el de CONTAINER desarma la maleta ajena y suelta sus componentes. Es decir,
 * un taller podia destruir existencias de otro.
 *
 * Se prueba contra el SERVICIO y contra las columnas reales, no contra un mock:
 * lo que hay que demostrar es que ninguna fila del otro taller se toca.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TransformationWorkshopIsolationDatabaseTest {

    private static final String TEMPORARY_DATABASE = "transformation_isolation_it";

    @Autowired
    private TransformationService service;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private TransformationRepository transformationRepository;

    @Autowired
    private InventoryAcquisitionRepository acquisitionRepository;

    private Workshop own;
    private Workshop foreign;
    private UserAccount ownUser;

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

    @BeforeEach
    void setUp() {
        own = workshop("TALLER PROPIO");
        foreign = workshop("TALLER AJENO");
        ownUser = userOf(own);
        authenticateAs(ownUser);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Utilidades de datos ──────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Workshop workshop(String name) {
        return workshopRepository.save(Workshop.builder()
                .name(name + " " + unique())
                .address("DIRECCION")
                .build());
    }

    private UserAccount userOf(Workshop workshop) {
        // Los roles los siembra Flyway (V4) y `role` no hereda la auditoria,
        // asi que insertar uno nuevo violaria `created_at NOT NULL`: se
        // reutiliza el que ya existe.
        Role role = roleRepository.findAll().stream()
                .filter(candidate -> "OPERATOR".equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La migracion V4 deberia haber sembrado el rol OPERATOR"));
        Employee employee = employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("Ana")
                .lastName("Ruiz")
                .dpi(unique())
                .phoneNumber("55555555")
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        return userAccountRepository.save(UserAccount.builder()
                .role(role)
                .employee(employee)
                .username("user-" + unique())
                .password("x")
                .isActive(true)
                .build());
    }

    /** El taller SIEMPRE sale del token, nunca del cuerpo de la peticion. */
    private void authenticateAs(UserAccount account) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop",
                        account.getEmployee().getWorkshop().getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /**
     * Inventario completo y creible: la familia DECLARA sus caracteristicas
     * —sin eso el manejador SPECIAL rechaza cualquier `largo`— y las existencias
     * sembradas llevan detras un lote de produccion, porque consumir una unidad
     * sin respaldo de adquisicion tambien es un error de negocio.
     */
    private Inventory inventory(
            Workshop workshop,
            FamilyCategory category,
            Map<String, Object> attributes,
            int stock) {
        String code = unique();
        Family family = Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .attributes(new ArrayList<>())
                .build();
        for (String name : attributes.keySet()) {
            family.addAttribute(Attribute.builder()
                    .family(family)
                    .name(name)
                    .dataType(attributes.get(name) instanceof Boolean
                            ? AttributeDataType.BOOLEAN
                            : AttributeDataType.NUMBER)
                    .isRequired(false)
                    .build());
        }
        family = familyRepository.save(family);
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name("PIEZA " + code)
                .attributes(new HashMap<>(attributes))
                .build());
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(BigDecimal.TEN)
                .cost(BigDecimal.ONE)
                .stock(stock)
                .minStock(0)
                .build());
        if (stock > 0) {
            acquisitionRepository.save(InventoryAcquisition.builder()
                    .workshop(workshop)
                    .inventory(inventory)
                    .origin(AcquisitionOrigin.PRODUCTION)
                    .quantityReceived(stock)
                    .quantityRemaining(stock)
                    .acquiredAt(LocalDateTime.now())
                    .build());
        }
        return inventory;
    }

    private InventoryItem item(Inventory inventory, InventoryItemStatus status) {
        return itemRepository.save(InventoryItem.builder()
                .inventory(inventory)
                .status(status)
                .build());
    }

    private static Map<String, Object> lengthOf(int largo) {
        return Map.of("largo", largo, "tieneOrificio", false);
    }

    private static CreateTransformationRequestDTO special(
            UUID inputItem, int newLength) {
        return new CreateTransformationRequestDTO(
                inputItem, null, null, null,
                Map.of("largo", newLength), null, null, null);
    }

    private static CreateTransformationRequestDTO container(
            UUID inputItem, List<UUID> components) {
        return new CreateTransformationRequestDTO(
                inputItem, null, null, components, null, null, null, null);
    }

    // ── A. El taller transforma lo suyo ──────────────────────────

    @Test
    void transformaUnaPiezaEspecialDeSuPropioTaller() {
        Inventory ownInventory = inventory(
                own, FamilyCategory.SPECIAL, lengthOf(120), 1);
        InventoryItem ownItem = item(ownInventory, InventoryItemStatus.AVAILABLE);

        TransformationResponseDTO created = service.create(
                special(ownItem.getId(), 40));

        assertThat(created.workshop().id()).isEqualTo(own.getId());
        assertThat(created.items())
                .anyMatch(entry -> entry.role() == TransformationItemRole.INPUT);
        assertThat(itemRepository.findById(ownItem.getId()).orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.CONSUMED);
    }

    // ── B. El taller NO puede transformar lo ajeno ───────────────

    @Test
    void noTransformaUnaPiezaEspecialDeOtroTaller() {
        Inventory foreignInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 3);
        InventoryItem foreignItem = item(
                foreignInventory, InventoryItemStatus.AVAILABLE);

        long transformationsBefore = transformationRepository.count();
        long acquisitionsBefore = acquisitionRepository.count();
        long itemsBefore = itemRepository.count();

        assertThatThrownBy(() -> service.create(special(foreignItem.getId(), 40)))
                .isInstanceOf(EntityNotFoundException.class)
                // F/G/H/I: ni una fila cambia ni se crea.
                .satisfies(thrown -> {
                    assertThat(inventoryRepository.findById(foreignInventory.getId())
                            .orElseThrow().getStock()).isEqualTo(3);
                    assertThat(itemRepository.findById(foreignItem.getId())
                            .orElseThrow().getStatus())
                            .isEqualTo(InventoryItemStatus.AVAILABLE);
                    assertThat(transformationRepository.count())
                            .isEqualTo(transformationsBefore);
                    assertThat(acquisitionRepository.count())
                            .isEqualTo(acquisitionsBefore);
                    assertThat(itemRepository.count()).isEqualTo(itemsBefore);
                });
    }

    /** No se revela que la pieza existe en otro taller: se dice que no existe. */
    @Test
    void elMensajeNoDelataElTallerAjeno() {
        Inventory foreignInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 1);
        InventoryItem foreignItem = item(
                foreignInventory, InventoryItemStatus.AVAILABLE);

        assertThatThrownBy(() -> service.create(special(foreignItem.getId(), 40)))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageNotContainingAny(
                        foreign.getId().toString(),
                        foreign.getName(),
                        foreignInventory.getId().toString(),
                        foreignInventory.getProduct().getName());
    }

    /** Un id que no existe en ningun taller responde EXACTAMENTE igual. */
    @Test
    void unaPiezaAjenaYUnaInexistenteSonIndistinguibles() {
        Inventory foreignInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 1);
        InventoryItem foreignItem = item(
                foreignInventory, InventoryItemStatus.AVAILABLE);

        String foreignMessage = catchMessage(
                () -> service.create(special(foreignItem.getId(), 40)));
        String missingMessage = catchMessage(
                () -> service.create(special(UUID.randomUUID(), 40)));

        assertThat(foreignMessage).isEqualTo(missingMessage);
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Se esperaba un fallo y no lo hubo");
        } catch (RuntimeException thrown) {
            return thrown.getMessage();
        }
    }

    // ── C. Contenedor propio ─────────────────────────────────────

    @Test
    void desarmaUnaMaletaDeSuPropioTaller() {
        Inventory containerInventory = inventory(
                own, FamilyCategory.CONTAINER, Map.of(), 1);
        Inventory componentInventory = inventory(
                own, FamilyCategory.SPECIAL, lengthOf(120), 0);
        InventoryItem containerItem = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem component = item(
                componentInventory, InventoryItemStatus.UNAVAILABLE);
        containerItem.addItem(component);
        itemRepository.save(component);

        TransformationResponseDTO created = service.create(
                container(containerItem.getId(), null));

        assertThat(created.items())
                .filteredOn(entry -> entry.role() == TransformationItemRole.RELEASED)
                .singleElement()
                .satisfies(entry -> assertThat(entry.item().id())
                        .isEqualTo(component.getId()));
        assertThat(itemRepository.findById(component.getId()).orElseThrow()
                .getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    @Test
    void noDesarmaUnaMaletaDeOtroTaller() {
        Inventory containerInventory = inventory(
                foreign, FamilyCategory.CONTAINER, Map.of(), 1);
        Inventory componentInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 0);
        InventoryItem containerItem = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem component = item(
                componentInventory, InventoryItemStatus.UNAVAILABLE);
        containerItem.addItem(component);
        itemRepository.save(component);

        assertThatThrownBy(() -> service.create(
                container(containerItem.getId(), null)))
                .isInstanceOf(EntityNotFoundException.class);

        // El componente ajeno sigue dentro de su maleta y sin existencias.
        InventoryItem untouched = itemRepository
                .findById(component.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(untouched.getContainer()).isNotNull();
        assertThat(inventoryRepository.findById(componentInventory.getId())
                .orElseThrow().getStock()).isZero();
    }

    // ── D/E. Componentes seleccionados ───────────────────────────

    @Test
    void noLiberaUnComponenteDeOtroTaller() {
        Inventory containerInventory = inventory(
                own, FamilyCategory.CONTAINER, Map.of(), 1);
        Inventory ownComponentInventory = inventory(
                own, FamilyCategory.SPECIAL, lengthOf(120), 0);
        InventoryItem containerItem = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem ownComponent = item(
                ownComponentInventory, InventoryItemStatus.UNAVAILABLE);
        containerItem.addItem(ownComponent);
        itemRepository.save(ownComponent);

        Inventory foreignInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 0);
        InventoryItem foreignComponent = item(
                foreignInventory, InventoryItemStatus.UNAVAILABLE);

        assertThatThrownBy(() -> service.create(container(
                containerItem.getId(), List.of(foreignComponent.getId()))))
                .isInstanceOf(RuntimeException.class);

        assertThat(itemRepository.findById(foreignComponent.getId()).orElseThrow()
                .getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(inventoryRepository.findById(foreignInventory.getId())
                .orElseThrow().getStock()).isZero();
        assertThat(itemRepository.findById(containerItem.getId()).orElseThrow()
                .getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    @Test
    void noLiberaUnComponenteDeOtraMaletaDelMismoTaller() {
        Inventory containerInventory = inventory(
                own, FamilyCategory.CONTAINER, Map.of(), 2);
        Inventory componentInventory = inventory(
                own, FamilyCategory.SPECIAL, lengthOf(120), 0);

        InventoryItem targetContainer = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem ownComponent = item(
                componentInventory, InventoryItemStatus.UNAVAILABLE);
        targetContainer.addItem(ownComponent);
        itemRepository.save(ownComponent);

        InventoryItem otherContainer = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem otherComponent = item(
                componentInventory, InventoryItemStatus.UNAVAILABLE);
        otherContainer.addItem(otherComponent);
        itemRepository.save(otherComponent);

        assertThatThrownBy(() -> service.create(container(
                targetContainer.getId(), List.of(otherComponent.getId()))))
                .isInstanceOf(RuntimeException.class);

        InventoryItem untouched = itemRepository
                .findById(otherComponent.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(untouched.getContainer().getId()).isEqualTo(otherContainer.getId());
    }

    /**
     * El esquema NO impide una fila incoherente: `container_id` apunta a otro
     * `inventory_item` a secas, y cada unidad cuelga de su propio `inventory_id`,
     * asi que una maleta de este taller puede acabar conteniendo una unidad cuyo
     * inventario es de otro. Liberarla le sumaria existencias y un lote de
     * produccion al inventario ajeno.
     *
     * Se siembra justo esa fila y se comprueba que el desarmado se niega entero.
     */
    @Test
    void noLiberaUnComponenteIncoherenteAunqueEsteDentroDeLaMaleta() {
        Inventory containerInventory = inventory(
                own, FamilyCategory.CONTAINER, Map.of(), 1);
        Inventory foreignComponentInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 0);

        InventoryItem containerItem = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem intruder = item(
                foreignComponentInventory, InventoryItemStatus.UNAVAILABLE);
        containerItem.addItem(intruder);
        itemRepository.save(intruder);

        assertThatThrownBy(() -> service.create(
                container(containerItem.getId(), null)))
                .isInstanceOf(RuntimeException.class);

        // Nada del taller ajeno se movio, y la maleta sigue armada.
        InventoryItem untouched = itemRepository
                .findById(intruder.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(untouched.getContainer()).isNotNull();
        assertThat(inventoryRepository.findById(foreignComponentInventory.getId())
                .orElseThrow().getStock()).isZero();
        assertThat(itemRepository.findById(containerItem.getId()).orElseThrow()
                .getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    // ── Encadenado: la funcion nueva sigue en pie ────────────────

    /**
     * Regresion del flujo encadenado: desarmar la maleta propia devuelve sus
     * componentes con rol RELEASED, todos del taller autenticado, y uno de ellos
     * sirve como ENTRADA de la transformacion siguiente.
     */
    @Test
    void elEncadenadoConPiezasLiberadasSigueFuncionando() {
        Inventory containerInventory = inventory(
                own, FamilyCategory.CONTAINER, Map.of(), 1);
        Inventory componentInventory = inventory(
                own, FamilyCategory.SPECIAL, lengthOf(120), 0);

        InventoryItem containerItem = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem first = item(componentInventory, InventoryItemStatus.UNAVAILABLE);
        InventoryItem second = item(componentInventory, InventoryItemStatus.UNAVAILABLE);
        containerItem.addItem(first);
        containerItem.addItem(second);
        itemRepository.saveAll(List.of(first, second));

        TransformationResponseDTO parent = service.create(
                container(containerItem.getId(), null));

        List<UUID> released = parent.items().stream()
                .filter(entry -> entry.role() == TransformationItemRole.RELEASED)
                .map(entry -> entry.item().id())
                .toList();
        assertThat(released).containsExactlyInAnyOrder(first.getId(), second.getId());

        // Toda pieza liberada sigue colgando de un inventario de ESTE taller.
        for (UUID releasedId : released) {
            InventoryItem freed = itemRepository.findById(releasedId).orElseThrow();
            assertThat(freed.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
            // Se relee el inventario por id: fuera de la sesion, navegar el
            // proxy perezoso desde la unidad no es posible.
            assertThat(inventoryRepository.findById(componentInventory.getId())
                    .orElseThrow().getWorkshop().getId()).isEqualTo(own.getId());
        }

        // Y una de ellas entra como ENTRADA de la transformacion siguiente.
        TransformationResponseDTO child = service.create(special(first.getId(), 40));

        assertThat(child.workshop().id()).isEqualTo(own.getId());
        assertThat(child.id()).isNotEqualTo(parent.id());
        assertThat(itemRepository.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.CONSUMED);
    }

    /** Una pieza liberada por OTRO taller no sirve como entrada aqui. */
    @Test
    void unaPiezaLiberadaPorOtroTallerNoSirveComoEntrada() {
        UserAccount foreignUser = userOf(foreign);
        authenticateAs(foreignUser);

        Inventory containerInventory = inventory(
                foreign, FamilyCategory.CONTAINER, Map.of(), 1);
        Inventory componentInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 0);
        InventoryItem containerItem = item(
                containerInventory, InventoryItemStatus.AVAILABLE);
        InventoryItem component = item(
                componentInventory, InventoryItemStatus.UNAVAILABLE);
        containerItem.addItem(component);
        itemRepository.save(component);

        service.create(container(containerItem.getId(), null));
        assertThat(itemRepository.findById(component.getId()).orElseThrow()
                .getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);

        authenticateAs(ownUser);

        assertThatThrownBy(() -> service.create(special(component.getId(), 40)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(itemRepository.findById(component.getId()).orElseThrow()
                .getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    // ── Lectura: listar y consultar tampoco cruzan el limite ─────

    @Test
    void listarNoDevuelveTransformacionesDeOtroTaller() {
        Inventory ownInventory = inventory(
                own, FamilyCategory.SPECIAL, lengthOf(120), 1);
        service.create(special(
                item(ownInventory, InventoryItemStatus.AVAILABLE).getId(), 40));

        UserAccount foreignUser = userOf(foreign);
        authenticateAs(foreignUser);
        Inventory foreignInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 1);
        TransformationResponseDTO foreignTransformation = service.create(special(
                item(foreignInventory, InventoryItemStatus.AVAILABLE).getId(), 40));

        authenticateAs(ownUser);
        List<TransformationResponseDTO> visible = service.getAll(noFilters());

        assertThat(visible).isNotEmpty();
        assertThat(visible)
                .allSatisfy(row -> assertThat(row.workshop().id()).isEqualTo(own.getId()));
        assertThat(visible)
                .noneMatch(row -> row.id().equals(foreignTransformation.id()));
    }

    /**
     * Y no hay forma de PEDIR el de otro taller: el alcance sale del token, asi
     * que el parametro ya no existe en el DTO y no queda nada que falsear.
     */
    @Test
    void consultarPorIdNoAlcanzaLaTransformacionDeOtroTaller() {
        UserAccount foreignUser = userOf(foreign);
        authenticateAs(foreignUser);
        Inventory foreignInventory = inventory(
                foreign, FamilyCategory.SPECIAL, lengthOf(120), 1);
        TransformationResponseDTO foreignTransformation = service.create(special(
                item(foreignInventory, InventoryItemStatus.AVAILABLE).getId(), 40));

        authenticateAs(ownUser);

        assertThatThrownBy(() -> service.get(foreignTransformation.id()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageNotContainingAny(foreign.getId().toString(), foreign.getName());
    }

    private static SearchTransformationRequestDTO noFilters() {
        return new SearchTransformationRequestDTO(null, null, null);
    }
}
