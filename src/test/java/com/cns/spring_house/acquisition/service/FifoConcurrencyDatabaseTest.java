package com.cns.spring_house.acquisition.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.service.InventoryService;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.service.SaleService;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El FIFO bajo concurrencia REAL: hilos de verdad, transacciones de verdad y
 * PostgreSQL de verdad.
 *
 * Esta prueba no se puede sustituir por una unitaria con `synchronized`. Lo que
 * protege el modelo es `SELECT ... FOR UPDATE` sobre las filas de lote, y eso
 * solo existe cuando hay dos transacciones compitiendo dentro del motor. Un
 * `synchronized` en Java demostraria unicamente que dos hilos de la MISMA JVM no
 * se pisan, que no es la situacion real: en produccion son dos peticiones que
 * pueden caer en procesos distintos.
 *
 * El patron de escritura que el cerrojo elimina es el clasico:
 *
 * <pre>
 * leer remanente -> decidir en Java -> guardar
 * </pre>
 *
 * Sin bloquear, dos ventas leen las mismas diez unidades disponibles, las dos
 * concluyen que alcanza para siete y las dos guardan: se sirven catorce.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class FifoConcurrencyDatabaseTest {

    private static final String TEMPORARY_DATABASE = "fifo_concurrency_it";

    @Autowired private InventoryService inventoryService;
    @Autowired private SaleService saleService;
    @Autowired private InventoryAcquisitionRepository acquisitionRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private SaleDetailRepository detailRepository;
    @Autowired private WorkshopRepository workshopRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CustomerRepository customerRepository;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Mas holgado que el resto de pruebas de base: aqui hay hilos que
        // compiten de verdad y cada uno necesita su conexion, o el "bloqueo" que
        // se observaria seria el del pool y no el de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Un lote de 10. Dos ventas simultaneas piden 7 cada una.
     *
     * Solo una puede prosperar: 7 + 7 = 14 no cabe en 10. Lo que se comprueba no
     * es cual gana —eso lo decide el orden en que PostgreSQL concede el cerrojo—
     * sino que el resultado sea SIEMPRE uno de los dos estados legales, nunca el
     * tercero en el que las dos creyeron tener existencias.
     */
    @Test
    void twoConcurrentSalesCannotConsumeTheSameUnits() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop);
        Inventory inventory = inventory(workshop);
        Jwt token = tokenFor(workshop);

        inventoryService.addStock(inventory.getId(), new AddStockRequestDTO(
                10, supplier.getId(), AcquisitionType.NATIONAL,
                new BigDecimal("100.00"), null));

        Outcome outcome = runConcurrentSales(token, inventory, 7, 7);

        // Exactamente una prospera: nunca las dos, nunca ninguna.
        assertThat(outcome.succeeded())
                .as("solo una cabe; fallos: %s", outcome.failures())
                .isEqualTo(1);

        // Y el estado final es el de UNA venta de siete.
        assertThat(remaining(inventory)).isEqualTo(3);
        assertThat(reload(inventory).getStock()).isEqualTo(3);
        assertThat(soldUnits(inventory)).isEqualTo(7);
        assertConsistency(inventory);
    }

    /**
     * Las dos caben. Aqui el cerrojo no debe rechazar nada: serializar no es
     * bloquear ventas legitimas, y una prueba que solo mirara el caso de
     * conflicto no distinguiria un cerrojo correcto de uno que rechaza siempre.
     */
    @Test
    void twoConcurrentSalesThatBothFitAreBothServed() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop);
        Inventory inventory = inventory(workshop);
        Jwt token = tokenFor(workshop);

        inventoryService.addStock(inventory.getId(), new AddStockRequestDTO(
                10, supplier.getId(), AcquisitionType.NATIONAL,
                new BigDecimal("100.00"), null));

        Outcome outcome = runConcurrentSales(token, inventory, 4, 6);

        assertThat(outcome.succeeded())
                .as("ambas caben; fallos: %s", outcome.failures())
                .isEqualTo(2);
        assertThat(remaining(inventory)).isZero();
        assertThat(reload(inventory).getStock()).isZero();
        assertThat(soldUnits(inventory)).isEqualTo(10);
        assertConsistency(inventory);
    }

    /**
     * Ocho ventas simultaneas de 3 sobre 10 unidades repartidas en dos lotes.
     *
     * Como mucho caben tres (9 unidades). Ademas de la cuenta, esto ejercita el
     * cruce de lotes bajo contencion: la tercera venta tiene que tomar de los dos
     * lotes a la vez mientras otras cinco compiten.
     */
    @Test
    void manyConcurrentSalesNeverOverConsumeAcrossLots() throws Exception {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop);
        Inventory inventory = inventory(workshop);
        Jwt token = tokenFor(workshop);

        inventoryService.addStock(inventory.getId(), new AddStockRequestDTO(
                6, supplier.getId(), AcquisitionType.NATIONAL,
                new BigDecimal("100.00"), null));
        inventoryService.addStock(inventory.getId(), new AddStockRequestDTO(
                4, supplier.getId(), AcquisitionType.IMPORTATION,
                new BigDecimal("120.00"), null));

        Outcome outcome = runConcurrentSales(token, inventory, 3, 3, 3, 3, 3, 3, 3, 3);

        assertThat(outcome.succeeded())
                .as("caben tres de ocho; fallos: %s", outcome.failures())
                .isEqualTo(3);
        assertThat(soldUnits(inventory)).isEqualTo(9);
        assertThat(remaining(inventory)).isEqualTo(1);
        assertThat(reload(inventory).getStock()).isEqualTo(1);
        assertConsistency(inventory);
    }

    /**
     * El invariante que no puede romperse nunca, comprobado LEYENDO LA BASE y no
     * las entidades en memoria.
     */
    private void assertConsistency(Inventory inventory) {
        assertThat(acquisitionRepository.findAll())
                .filteredOn(lot -> lot.getInventory().getId().equals(inventory.getId()))
                .allSatisfy(lot -> {
                    assertThat(lot.getQuantityRemaining())
                            .as("un remanente nunca puede ser negativo")
                            .isGreaterThanOrEqualTo(0);
                    assertThat(lot.getQuantityRemaining())
                            .as("un lote no puede devolver mas de lo que recibio")
                            .isLessThanOrEqualTo(lot.getQuantityReceived());
                });

        assertThat((long) reload(inventory).getStock())
                .as("inventory.stock debe igualar la suma de remanentes")
                .isEqualTo(acquisitionRepository.sumRemaining(inventory.getId()));
    }

    // ── Ejecucion concurrente ────────────────────────────────────

    /**
     * {@code failures} guarda POR QUE fallo cada venta que no prospero. Un
     * `catch` mudo en una prueba de concurrencia es una trampa: convierte un
     * interbloqueo o un fallo de configuracion en "no habia existencias", que es
     * justo el diagnostico equivocado.
     */
    private record Outcome(int succeeded, int failed, List<String> failures) { }

    /**
     * Lanza una venta por cantidad pedida, todas soltadas a la vez con una
     * barrera para que de verdad compitan, y espera a todas.
     *
     * Un fallo NO es un error de la prueba: cuando no hay existencias, la venta
     * TIENE que fallar. Lo que se cuenta es cuantas prosperaron, y las
     * invariantes se comprueban despues contra la base.
     */
    private Outcome runConcurrentSales(
            Jwt token, Inventory inventory, int... quantities) throws Exception {

        // El cliente se crea ANTES de soltar los hilos y se reutiliza. Crearlo
        // dentro de cada hilo metia una segunda fuente de contencion —el UNIQUE
        // de `customer.nit`— en una prueba que mide el FIFO, y un choque ahi se
        // habria leido como "no habia existencias".
        UUID sharedCustomer = customer();

        CyclicBarrier startTogether = new CyclicBarrier(quantities.length);
        ExecutorService pool = Executors.newFixedThreadPool(quantities.length);
        List<String> failures = java.util.Collections.synchronizedList(
                new ArrayList<>());

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int quantity : quantities) {
            tasks.add(() -> {
                // Cada hilo lleva su propio SecurityContext.
                SecurityContextHolder.getContext().setAuthentication(
                        new JwtAuthenticationToken(token));
                try {
                    startTogether.await(15, TimeUnit.SECONDS);
                    SaleResponseDTO sale = saleService.create(
                            new CreateSaleRequestDTO(sharedCustomer, null));
                    saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                            inventory.getProduct().getId(), null, null,
                            quantity, null, null));
                    return true;
                } catch (RuntimeException failure) {
                    failures.add(failure.getClass().getSimpleName()
                            + ": " + failure.getMessage());
                    return false;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }

        List<Future<Boolean>> results;
        try {
            results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        int succeeded = 0;
        int failed = 0;
        for (Future<Boolean> result : results) {
            if (Boolean.TRUE.equals(result.get())) {
                succeeded++;
            } else {
                failed++;
            }
        }
        return new Outcome(succeeded, failed, List.copyOf(failures));
    }

    // ── Lecturas ─────────────────────────────────────────────────

    private long remaining(Inventory inventory) {
        return acquisitionRepository.sumRemaining(inventory.getId());
    }

    /** Unidades que de verdad acabaron en una linea de venta persistida. */
    private int soldUnits(Inventory inventory) {
        return detailRepository.findAll().stream()
                .filter(detail -> detail.getProduct().getId()
                        .equals(inventory.getProduct().getId()))
                .mapToInt(SaleDetail::getQuantity)
                .sum();
    }

    private Inventory reload(Inventory inventory) {
        return inventoryRepository.findById(inventory.getId()).orElseThrow();
    }

    // ── Datos de partida ─────────────────────────────────────────

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private final Map<UUID, UserAccount> accounts = new HashMap<>();

    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .isActive(true)
                .build());
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokenFor(workshop)));
        return workshop;
    }

    private Jwt tokenFor(Workshop workshop) {
        UserAccount account = accounts.computeIfAbsent(
                workshop.getId(), ignored -> newAccount(workshop));
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
    }

    private UserAccount newAccount(Workshop workshop) {
        Employee employee = employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Role role = roleRepository.findAll().stream()
                .filter(candidate -> "CONTROL".equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El rol CONTROL no esta sembrado en la base de prueba"));
        return userAccountRepository.save(UserAccount.builder()
                .employee(employee)
                .role(role)
                .username("user" + unique())
                .password("secret")
                .build());
    }

    private Supplier supplier(Workshop workshop) {
        return supplierRepository.save(Supplier.builder()
                .workshop(workshop)
                .name("PROVEEDOR " + unique())
                .build());
    }

    /** `customer.nit` es UNIQUE global, asi que el valor sale de un contador. */
    private static final java.util.concurrent.atomic.AtomicLong NIT_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(100_000_000L);

    private UUID customer() {
        Customer customer = customerRepository.save(Customer.builder()
                .name("CLIENTE " + unique())
                .nit(String.valueOf(NIT_SEQUENCE.incrementAndGet()))
                .build());
        return customer.getId();
    }

    private Inventory inventory(Workshop workshop) {
        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name("NORMAL " + code)
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name("PRODUCTO " + code)
                .attributes(new HashMap<>())
                .build());
        return inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("500.00"))
                .cost(new BigDecimal("1.00"))
                .stock(0)
                .minStock(0)
                .build());
    }

}
