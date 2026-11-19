package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * Dos cajas estrenando a la vez el precio del MISMO producto sin precio.
 *
 * Es el caso que preocupa de la asignacion de precio dentro de la venta: dos
 * vendedores miran la misma pieza a cero, uno escribe Q250 y el otro Q300 en el
 * mismo momento. Sin cerrojo los dos leerian el mismo cero, los dos se creerian
 * los primeros en fijarlo, y el catalogo acabaria con el precio del que
 * escribiera ultimo sin que nadie lo supiera.
 *
 * El cerrojo que ya toma la venta sobre la fila de inventario —el mismo que
 * protege las existencias— serializa tambien esto, y el resultado deja de
 * depender del azar:
 *
 * <ul>
 * <li>La primera transaccion en entrar fija el precio del taller.</li>
 * <li>La segunda espera, y al despertar relee un inventario que YA tiene
 * precio: su importe pasa a ser un precio especial de SU venta y el catalogo
 * conserva el que se asigno primero.</li>
 * <li>Cada linea cobra lo que su cajero escribio. Ninguna hereda la de la
 * otra.</li>
 * </ul>
 *
 * No es "el ultimo gana": es "el primero asigna, el resto son descuentos", que
 * es la misma regla que aplica a cualquier producto ya con precio.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UnpricedItemConcurrencyDatabaseTest {

    private static final String TEMPORARY_DATABASE = "unpriced_concurrency_it";

    @Autowired
    private SaleService service;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryAcquisitionRepository acquisitionRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Dos hilos compiten de verdad, asi que el pool tiene que dar para los
        // dos a la vez; con una sola conexion la prueba mediria la espera del
        // pool y no el cerrojo de la fila.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Jwt tokenFor(Workshop workshop) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
    }

    @Test
    void twoSellersWhoSawTheProductUnpricedCannotBothPriceIt()
            throws Exception {

        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());

        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name("NORMAL " + code)
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name("PRODUCTO SIN PRECIO " + code)
                .attributes(new HashMap<>())
                .build());

        // Sin precio asignado, con existencias para las dos ventas.
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(BigDecimal.ZERO)
                .cost(new BigDecimal("10.00"))
                .stock(10)
                .minStock(0)
                .build());

        Supplier supplier = supplierRepository.save(Supplier.builder()
                .workshop(workshop)
                .name("PROVEEDOR " + unique())
                .build());
        acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(workshop)
                .inventory(inventory)
                .supplier(supplier)
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(AcquisitionType.NATIONAL)
                .quantityReceived(10)
                .quantityRemaining(10)
                .unitCost(new BigDecimal("10.00"))
                .acquiredAt(LocalDateTime.now())
                .build());

        Sale first = openSale(workshop);
        Sale second = openSale(workshop);

        Jwt token = tokenFor(workshop);
        BigDecimal[] prices = {new BigDecimal("250.00"), new BigDecimal("300.00")};
        Sale[] sales = {first, second};

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int index = 0; index < 2; index++) {
            final BigDecimal price = prices[index];
            final UUID saleId = sales[index].getId();
            tasks.add(() -> {
                // Cada hilo lleva su propio SecurityContext.
                SecurityContextHolder.getContext()
                        .setAuthentication(new JwtAuthenticationToken(token));
                try {
                    startTogether.await(15, TimeUnit.SECONDS);
                    // Los dos lo vieron SIN precio (0) antes de confirmar.
                    service.addDetail(saleId, new AddSaleDetailRequestDTO(
                            product.getId(), null, null, 1, price, null, null, null, null, null,
                            BigDecimal.ZERO));
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
        for (Future<Boolean> result : results) {
            if (Boolean.TRUE.equals(result.get())) {
                succeeded++;
            }
        }

        // Los dos vieron el producto SIN precio. El primero que toma el
        // inventario fija el catalogo con su cifra; para el segundo ese "sin
        // precio" ya no es verdad: su cifra pasaria a ser, en silencio, un
        // precio especial sobre un catalogo que no vio. Se rechaza con 409 y
        // reconfirma. Antes —con la precondicion opcional— prosperaban las dos.
        assertThat(succeeded)
                .as("solo una linea debia agregarse; fallos: %s", failures)
                .isEqualTo(1);
        assertThat(failures).singleElement().asString()
                .startsWith("PriceConflictException")
                .contains("Revise y confirme el precio de nuevo");

        // El catalogo es exactamente el de quien entro primero, y su linea lo lleva.
        BigDecimal catalog = inventoryRepository.findById(inventory.getId())
                .orElseThrow()
                .getPrice();
        assertThat(catalog).isIn(new BigDecimal("250.00"), new BigDecimal("300.00"));
        List<SaleDetail> lines = new ArrayList<>();
        lines.addAll(saleRepository.findById(first.getId()).orElseThrow().getDetails());
        lines.addAll(saleRepository.findById(second.getId()).orElseThrow().getDetails());
        assertThat(lines).singleElement()
                .satisfies(line -> assertThat(line.getPrice()).isEqualByComparingTo(catalog));

        // Y el rechazado no tomo nada: una sola unidad salio del stock.
        assertThat(inventoryRepository.findById(inventory.getId())
                .orElseThrow().getStock()).isEqualTo(9);
    }

    private SaleDetail onlyDetailOf(Sale sale) {
        List<SaleDetail> details = saleRepository.findById(sale.getId())
                .orElseThrow()
                .getDetails();
        assertThat(details).hasSize(1);
        return details.get(0);
    }

    private Sale openSale(Workshop workshop) {
        Employee employee = employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        return saleRepository.save(Sale.builder()
                .taxMode(TaxMode.UNRESOLVED)
                .workshop(workshop)
                .employee(employee)
                .build());
    }

    /** Solo para leer el catalogo que el vendedor tenia a la vista (ver SeenCatalog). */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate seenJdbc;

    /** Agregar como lo hace la caja: con el catalogo que el vendedor tenia a la vista. */
    private com.cns.spring_house.sale.dto.SaleResponseDTO addSeen(
            UUID saleId, com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO dto) {
        return service.addDetail(saleId, com.cns.spring_house.testsupport.SeenCatalog.seen(seenJdbc, dto));
    }

}
