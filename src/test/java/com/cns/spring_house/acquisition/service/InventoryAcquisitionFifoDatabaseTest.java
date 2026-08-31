package com.cns.spring_house.acquisition.service;

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
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

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
import com.cns.spring_house.acquisition.entity.SaleDetailCostAllocation;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.acquisition.repository.SaleDetailCostAllocationRepository;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.dto.InventoryResponseDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.inventory.service.InventoryService;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.service.SaleService;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El modelo de lotes de punta a punta, contra PostgreSQL real y a traves de los
 * SERVICIOS de produccion: registrar una entrada, venderla, deshacerla.
 *
 * No hay transaccion de prueba envolviendo las llamadas. La unica frontera es la
 * del `@Transactional` de cada servicio, que es lo que permite comprobar de
 * verdad que un fallo a mitad de camino no deja lotes medio consumidos ni stock
 * descontado. Mismo criterio que {@code SaleServiceDatabaseTest}.
 *
 * Cada prueba monta su propio taller.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class InventoryAcquisitionFifoDatabaseTest {

    private static final String TEMPORARY_DATABASE = "acquisition_fifo_it";

    private static final BigDecimal CIEN = new BigDecimal("100.00");
    private static final BigDecimal CIENTO_VEINTE = new BigDecimal("120.00");

    @Autowired private InventoryService inventoryService;
    @Autowired private SaleService saleService;
    @Autowired private InventoryAcquisitionRepository acquisitionRepository;
    @Autowired private SaleDetailCostAllocationRepository allocationRepository;
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

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ═══ Registro de la adquisicion ══════════════════════════════

    @Test
    void aPurchaseIncrementsStockAndLeavesItsLot() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        InventoryResponseDTO response = inventoryService.addStock(
                inventory.getId(),
                purchase(supplier, AcquisitionType.NATIONAL, 15, "110.00"));

        assertThat(response.stock()).isEqualTo(15);

        InventoryAcquisition lot = onlyLot(inventory);
        assertThat(lot.getQuantityReceived()).isEqualTo(15);
        // Nace intacto: nada se ha consumido todavia.
        assertThat(lot.getQuantityRemaining()).isEqualTo(15);
        assertThat(lot.getUnitCost()).isEqualByComparingTo("110.00");
        assertThat(lot.getSupplier().getId()).isEqualTo(supplier.getId());
        assertThat(lot.getAcquisitionType()).isEqualTo(AcquisitionType.NATIONAL);
        assertThat(lot.getOrigin()).isEqualTo(AcquisitionOrigin.PURCHASE);
        // La fecha la pone el servidor, no la peticion.
        assertThat(lot.getAcquiredAt()).isNotNull();
        assertThat(lot.getEmployee()).isNotNull();
        // Total derivado, nunca enviado por el cliente: 15 x 110 = 1650.
        assertThat(lot.totalCost()).isEqualByComparingTo("1650.00");
    }

    @Test
    void aPurchaseRecordsTheImportationType() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Importadora del Norte");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        inventoryService.addStock(inventory.getId(),
                purchase(supplier, AcquisitionType.IMPORTATION, 5, "300.00"));

        assertThat(onlyLot(inventory).getAcquisitionType())
                .isEqualTo(AcquisitionType.IMPORTATION);
    }

    /**
     * SPECIAL tambien es una compra: un lote para las N unidades recibidas, y N
     * unidades fisicas. El lote no se parte en uno por pieza.
     */
    @Test
    void aSpecialPurchaseCreatesOneLotAndOnePhysicalUnitPerItem() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.SPECIAL, 0);

        inventoryService.addStock(inventory.getId(),
                purchase(supplier, AcquisitionType.NATIONAL, 3, "90.00"));

        assertThat(onlyLot(inventory).getQuantityReceived()).isEqualTo(3);
        assertThat(inventoryService.getItems(inventory.getId())).hasSize(3);
        assertThat(reload(inventory).getStock()).isEqualTo(3);
    }

    // ═══ Validacion de la adquisicion ════════════════════════════

    @Test
    void rejectsAPurchaseWithoutSupplier() {
        Workshop workshop = authenticatedWorkshop();
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        assertThatThrownBy(() -> inventoryService.addStock(
                inventory.getId(),
                new AddStockRequestDTO(5, null, AcquisitionType.NATIONAL, CIEN, null)))
                .isInstanceOf(ValidationException.class);

        assertThatNothingWasRegistered(inventory);
    }

    @Test
    void rejectsAPurchaseWithoutAcquisitionType() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        assertThatThrownBy(() -> inventoryService.addStock(
                inventory.getId(),
                new AddStockRequestDTO(5, supplier.getId(), null, CIEN, null)))
                .isInstanceOf(ValidationException.class);

        assertThatNothingWasRegistered(inventory);
    }

    @Test
    void rejectsAPurchaseWithoutUnitCost() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        assertThatThrownBy(() -> inventoryService.addStock(
                inventory.getId(),
                new AddStockRequestDTO(
                        5, supplier.getId(), AcquisitionType.NATIONAL, null, null)))
                .isInstanceOf(ValidationException.class);

        assertThatNothingWasRegistered(inventory);
    }

    /**
     * El cruce entre talleres es un fallo automatico del modelo: conocer el UUID
     * de un proveedor ajeno no puede bastar para atribuirle una compra.
     */
    @Test
    void rejectsASupplierFromAnotherWorkshop() {
        Workshop otherWorkshop = authenticatedWorkshop();
        Supplier foreignSupplier = supplier(otherWorkshop, "Proveedor Ajeno");

        Workshop workshop = authenticatedWorkshop();
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        assertThatThrownBy(() -> inventoryService.addStock(
                inventory.getId(),
                purchase(foreignSupplier, AcquisitionType.NATIONAL, 5, "100.00")))
                .isInstanceOf(EntityNotFoundException.class);

        assertThatNothingWasRegistered(inventory);
    }

    @Test
    void rejectsAnInventoryFromAnotherWorkshop() {
        Workshop otherWorkshop = authenticatedWorkshop();
        Inventory foreignInventory = inventory(otherWorkshop, FamilyCategory.NORMAL, 0);

        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");

        assertThatThrownBy(() -> inventoryService.addStock(
                foreignInventory.getId(),
                purchase(supplier, AcquisitionType.NATIONAL, 5, "100.00")))
                .isInstanceOf(EntityNotFoundException.class);

        assertThatNothingWasRegistered(foreignInventory);
    }

    /**
     * El costo de REFERENCIA pasa a ser el de la ultima compra. Se comprueba
     * explicitamente porque es la unica semantica que le queda a esa columna, y
     * confundirla con verdad historica es justo lo que este modelo corrige.
     */
    @Test
    void theReferenceCostBecomesTheLatestPurchaseCost() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        inventoryService.addStock(inventory.getId(),
                purchase(supplier, AcquisitionType.NATIONAL, 10, "100.00"));
        assertThat(reload(inventory).getCost()).isEqualByComparingTo("100.00");

        inventoryService.addStock(inventory.getId(),
                purchase(supplier, AcquisitionType.NATIONAL, 10, "120.00"));
        assertThat(reload(inventory).getCost()).isEqualByComparingTo("120.00");

        // Y los DOS lotes conservan su propio costo: el primero no se reescribio.
        assertThat(lotsByAge(inventory))
                .extracting(InventoryAcquisition::getUnitCost)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("120.00"));
    }

    // ═══ FIFO ════════════════════════════════════════════════════

    /**
     * Caso 1 de la matriz: vender 4 de un lote de 10 a Q100 consume solo ese
     * lote y cuesta exactamente Q400.
     */
    @Test
    void consumesTheOldestLotFirst() {
        Fixture fixture = twoLots();

        SaleDetail detail = sellNormal(fixture, 4);

        assertThat(detail.getCostTotal()).isEqualByComparingTo("400.00");
        assertThat(remainingByAge(fixture.inventory)).containsExactly(6, 10);
    }

    /**
     * Caso 2: la siguiente venta agota el primer lote y sigue en el segundo.
     * 6 x Q100 + 2 x Q120 = Q840. Es la prueba de que una linea NO tiene un
     * unico costo unitario.
     */
    @Test
    void aSingleSaleLineSpansTwoLots() {
        Fixture fixture = twoLots();
        sellNormal(fixture, 4);

        SaleDetail detail = sellNormal(fixture, 8);

        assertThat(detail.getCostTotal()).isEqualByComparingTo("840.00");
        assertThat(remainingByAge(fixture.inventory)).containsExactly(0, 8);

        // Y la traza dice de donde salio cada parte.
        List<SaleDetailCostAllocation> allocations =
                allocationRepository.findBySaleDetailId(detail.getId());
        assertThat(allocations).hasSize(2);
        assertThat(allocations)
                .extracting(SaleDetailCostAllocation::getQuantity)
                .containsExactlyInAnyOrder(6, 2);
        assertThat(allocations)
                .extracting(SaleDetailCostAllocation::getUnitCostSnapshot)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactlyInAnyOrder(CIEN, CIENTO_VEINTE);
        // Las partes suman EXACTAMENTE el costo de la linea.
        assertThat(allocations.stream()
                .map(SaleDetailCostAllocation::getSubtotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(detail.getCostTotal());
        // Y las cantidades suman la cantidad vendida.
        assertThat(allocations.stream()
                .mapToInt(SaleDetailCostAllocation::getQuantity).sum())
                .isEqualTo(detail.getQuantity());
    }

    /** Caso 3: vender exactamente lo que queda cierra el lote en cero. */
    @Test
    void aLotClosesAtZeroWhenExactlyExhausted() {
        Fixture fixture = twoLots();

        SaleDetail detail = sellNormal(fixture, 10);

        assertThat(detail.getCostTotal()).isEqualByComparingTo("1000.00");
        assertThat(remainingByAge(fixture.inventory)).containsExactly(0, 10);
    }

    /**
     * Caso 4: sin existencias suficientes la operacion ENTERA se rechaza. Lo que
     * importa no es el error sino que no quede NADA a medias.
     */
    @Test
    void insufficientStockLeavesNoPartialConsumption() {
        Fixture fixture = twoLots();

        assertThatThrownBy(() -> sellNormal(fixture, 21))
                .isInstanceOf(ValidationException.class);

        assertThat(remainingByAge(fixture.inventory)).containsExactly(10, 10);
        assertThat(reload(fixture.inventory).getStock()).isEqualTo(20);
        assertThatStockMatchesLots(fixture.inventory);
    }

    /**
     * Caso 5: dos lotes con el MISMO instante de adquisicion.
     *
     * Determinismo NO significa que dos escenarios distintos cuesten igual —los
     * uuid son aleatorios, asi que cual de los dos lotes va primero cambia de un
     * escenario a otro—. Significa que el orden lo decide una REGLA y no el
     * motor: empatados en `acquiredAt`, consume el de id menor, siempre, sin que
     * quede margen para que PostgreSQL elija.
     *
     * Por eso cada escenario deriva su propia expectativa de la regla en vez de
     * comparar dos escenarios entre si, y se repite varias veces para cubrir los
     * dos ordenes posibles de id.
     */
    @Test
    void tieBreaksDeterministicallyByIdWhenAcquiredAtMatches() {
        for (int attempt = 0; attempt < 6; attempt++) {
            assertTiedLotsConsumeTheLowerId();
        }
    }

    /**
     * Monta dos lotes de 5 con el MISMO `acquiredAt` y costos distintos, vende
     * exactamente 5 y comprueba que se consumio el de id menor —en el orden de
     * uuid de PostgreSQL, que es quien ejecuta el ORDER BY—, dejando el otro
     * intacto.
     */
    private void assertTiedLotsConsumeTheLowerId() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        java.time.LocalDateTime sameInstant = java.time.LocalDateTime.now();
        InventoryAcquisition cheap = seedLot(
                workshop, inventory, supplier, 5, "100.00", sameInstant);
        InventoryAcquisition pricey = seedLot(
                workshop, inventory, supplier, 5, "120.00", sameInstant);
        bumpStock(inventory, 10);

        InventoryAcquisition expected =
                POSTGRES_UUID_ORDER.compare(cheap.getId(), pricey.getId()) < 0
                        ? cheap
                        : pricey;

        SaleDetail detail = sellNormal(
                new Fixture(workshop, supplier, inventory), 5);

        // Se consumio entero el lote de id menor...
        assertThat(detail.getCostTotal()).isEqualByComparingTo(
                expected.getUnitCost().multiply(BigDecimal.valueOf(5)));
        // ...y el otro sigue intacto: el primero a cero, el segundo a cinco.
        assertThat(remainingByAge(inventory)).containsExactly(0, 5);
        // La traza confirma DE QUE lote salio, no solo cuanto costo.
        assertThat(allocationRepository.findBySaleDetailId(detail.getId()))
                .singleElement()
                .satisfies(allocation -> assertThat(
                        allocation.getAcquisition().getId())
                        .isEqualTo(expected.getId()));
    }

    // ═══ Inmutabilidad del costo historico ═══════════════════════

    /**
     * El costo de una venta pasada NO se mueve porque despues entre mercaderia
     * mas cara o porque el proveedor se renombre. Es la propiedad que hace
     * defendible la ganancia historica.
     */
    @Test
    void historicalCostSurvivesLaterPurchasesAndRenames() {
        Fixture fixture = twoLots();
        SaleDetail detail = sellNormal(fixture, 4);
        BigDecimal capturedCost = detail.getCostTotal();

        // Entra mercaderia mucho mas cara y el costo de referencia sube.
        inventoryService.addStock(fixture.inventory.getId(),
                purchase(fixture.supplier, AcquisitionType.NATIONAL, 10, "999.00"));
        // Y el proveedor cambia de nombre.
        Supplier supplier = supplierRepository.findById(fixture.supplier.getId())
                .orElseThrow();
        supplier.setName("Resortes Guatemala Renombrado");
        supplierRepository.save(supplier);

        SaleDetail reloaded = detailRepository.findById(detail.getId()).orElseThrow();
        assertThat(reloaded.getCostTotal()).isEqualByComparingTo(capturedCost);
        assertThat(reloaded.getCostTotal()).isEqualByComparingTo("400.00");

        assertThat(allocationRepository.findBySaleDetailId(detail.getId()))
                .extracting(SaleDetailCostAllocation::getUnitCostSnapshot)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(CIEN);
    }

    // ═══ Deshacer: quitar, anular, revertir ══════════════════════

    @Test
    void removingAPendingDetailRestoresTheExactLots() {
        Fixture fixture = twoLots();
        sellNormal(fixture, 4);
        SaleDetail spanning = sellNormal(fixture, 8);

        saleService.removeDetail(spanning.getId());

        // Las 6 vuelven al lote viejo y las 2 al nuevo, no al reves.
        assertThat(remainingByAge(fixture.inventory)).containsExactly(6, 10);
        assertThat(reload(fixture.inventory).getStock()).isEqualTo(16);
        assertThat(allocationRepository.findBySaleDetailId(spanning.getId()))
                .isEmpty();
        assertThatStockMatchesLots(fixture.inventory);
    }

    @Test
    void cancellingAPendingSaleRestoresTheExactLots() {
        Fixture fixture = twoLots();
        SaleResponseDTO sale = openSaleWith(fixture, 8);

        saleService.cancel(sale.id(), new CancelSaleRequestDTO("Cliente desistio"));

        assertThat(remainingByAge(fixture.inventory)).containsExactly(10, 10);
        assertThat(reload(fixture.inventory).getStock()).isEqualTo(20);
        assertThatStockMatchesLots(fixture.inventory);
    }

    /**
     * Revertir una venta ya emitida SI devuelve el inventario en este dominio
     * —`SaleServiceImpl.reverse` reutiliza el mismo `restore` de los handlers—,
     * asi que los lotes tienen que volver con el. No se inventa la semantica: se
     * comprueba la que el dominio ya tenia.
     */
    @Test
    void reversingACompletedSaleRestoresTheExactLots() {
        Fixture fixture = twoLots();
        SaleResponseDTO sale = openSaleWith(fixture, 12);
        saleService.completeSale(sale.id(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));

        assertThat(remainingByAge(fixture.inventory)).containsExactly(0, 8);

        saleService.reverse(sale.id(), new ReverseSaleRequestDTO("Devolucion"));

        assertThat(remainingByAge(fixture.inventory)).containsExactly(10, 10);
        assertThat(reload(fixture.inventory).getStock()).isEqualTo(20);
        assertThatStockMatchesLots(fixture.inventory);
    }

    /** Completar la venta NO mueve nada: el stock ya salio al agregar la linea. */
    @Test
    void completingASaleDoesNotTouchTheLotsAgain() {
        Fixture fixture = twoLots();
        SaleResponseDTO sale = openSaleWith(fixture, 4);

        assertThat(remainingByAge(fixture.inventory)).containsExactly(6, 10);

        saleService.completeSale(sale.id(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));

        assertThat(remainingByAge(fixture.inventory)).containsExactly(6, 10);
        SaleDetail detail = onlyDetail(sale.id());
        assertThat(detail.getCostTotal()).isEqualByComparingTo("400.00");
    }

    // ═══ Ganancia historica ══════════════════════════════════════

    /**
     * La ganancia sale del subtotal HISTORICO menos el costo HISTORICO, de modo
     * que un ajuste manual de precio se sigue viendo. 4 x Q170 = Q680 de venta,
     * Q400 de costo, Q280 de ganancia.
     */
    @Test
    void grossProfitUsesTheHistoricalSubtotalAndCost() {
        Fixture fixture = twoLots();

        SaleDetail detail = sellNormalAt(fixture, 4, new BigDecimal("170.00"));

        assertThat(detail.getSubTotal()).isEqualByComparingTo("680.00");
        assertThat(detail.getCostTotal()).isEqualByComparingTo("400.00");
        assertThat(detail.getSubTotal().subtract(detail.getCostTotal()))
                .isEqualByComparingTo("280.00");
    }

    // ═══ Reconciliacion ══════════════════════════════════════════

    /**
     * El invariante del modelo. Se comprueba despues de una secuencia completa
     * —comprar, vender, quitar, volver a vender— porque es ahi donde una
     * asimetria entre consumo y devolucion apareceria.
     */
    @Test
    void stockAlwaysEqualsTheSumOfRemainingLots() {
        Fixture fixture = twoLots();
        assertThatStockMatchesLots(fixture.inventory);

        SaleDetail first = sellNormal(fixture, 7);
        assertThatStockMatchesLots(fixture.inventory);

        saleService.removeDetail(first.getId());
        assertThatStockMatchesLots(fixture.inventory);

        sellNormal(fixture, 15);
        assertThatStockMatchesLots(fixture.inventory);

        inventoryService.addStock(fixture.inventory.getId(),
                purchase(fixture.supplier, AcquisitionType.IMPORTATION, 4, "130.00"));
        assertThatStockMatchesLots(fixture.inventory);
    }

    private void assertThatStockMatchesLots(Inventory inventory) {
        assertThat((long) reload(inventory).getStock())
                .as("inventory.stock debe igualar la suma de remanentes")
                .isEqualTo(acquisitionRepository.sumRemaining(inventory.getId()));
    }

    private void assertThatNothingWasRegistered(Inventory inventory) {
        assertThat(lotsByAge(inventory)).isEmpty();
        assertThat(reload(inventory).getStock()).isZero();
    }

    // ── Apoyo ────────────────────────────────────────────────────

    /** Inventario NORMAL con dos lotes: 10 a Q100 y, despues, 10 a Q120. */
    private record Fixture(Workshop workshop, Supplier supplier, Inventory inventory) { }

    private Fixture twoLots() {
        Workshop workshop = authenticatedWorkshop();
        Supplier supplier = supplier(workshop, "Resortes Guatemala");
        Inventory inventory = inventory(workshop, FamilyCategory.NORMAL, 0);

        inventoryService.addStock(inventory.getId(),
                purchase(supplier, AcquisitionType.NATIONAL, 10, "100.00"));
        inventoryService.addStock(inventory.getId(),
                purchase(supplier, AcquisitionType.IMPORTATION, 10, "120.00"));

        return new Fixture(workshop, supplier, inventory);
    }

    private AddStockRequestDTO purchase(
            Supplier supplier, AcquisitionType type, int units, String unitCost) {
        return new AddStockRequestDTO(
                units, supplier.getId(), type, new BigDecimal(unitCost), null);
    }

    private SaleDetail sellNormal(Fixture fixture, int quantity) {
        return sellNormalAt(fixture, quantity, null);
    }

    private SaleDetail sellNormalAt(
            Fixture fixture, int quantity, BigDecimal price) {
        SaleResponseDTO sale = saleService.create(
                new CreateSaleRequestDTO(customer(), null));
        saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                fixture.inventory.getProduct().getId(), null, null,
                quantity, price, null));
        return onlyDetail(sale.id());
    }

    private SaleResponseDTO openSaleWith(Fixture fixture, int quantity) {
        SaleResponseDTO sale = saleService.create(
                new CreateSaleRequestDTO(customer(), null));
        saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                fixture.inventory.getProduct().getId(), null, null,
                quantity, null, null));
        return sale;
    }

    private SaleDetail onlyDetail(UUID saleId) {
        List<SaleDetail> details = detailRepository.findAll().stream()
                .filter(detail -> detail.getSale().getId().equals(saleId))
                .toList();
        assertThat(details).hasSize(1);
        return details.get(0);
    }

    /**
     * Orden de UUID tal y como lo hace PostgreSQL, que es quien ejecuta el
     * `ORDER BY` del FIFO.
     *
     * NO se puede usar {@link UUID#compareTo}: Java compara las dos mitades como
     * `long` con SIGNO, asi que un uuid cuyo bit alto esta a 1 le resulta
     * "menor" que uno que empieza por ceros. PostgreSQL compara los 16 bytes sin
     * signo. Las dos ordenaciones discrepan en aproximadamente la mitad de los
     * pares, y esta prueba lo descubrio: esperaba un lote y el FIFO consumia el
     * otro, sin que hubiera nada mal en el FIFO.
     *
     * El desempate real —el que hace reproducible el costo— es el de
     * PostgreSQL, asi que es el que la prueba tiene que reproducir.
     */
    private static final Comparator<UUID> POSTGRES_UUID_ORDER = (left, right) -> {
        int high = Long.compareUnsigned(
                left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(
                        left.getLeastSignificantBits(),
                        right.getLeastSignificantBits());
    };

    /** Lotes del inventario en el MISMO orden que consume el FIFO. */
    private List<InventoryAcquisition> lotsByAge(Inventory inventory) {
        return acquisitionRepository.findAll().stream()
                .filter(lot -> lot.getInventory().getId().equals(inventory.getId()))
                .sorted(Comparator
                        .comparing(InventoryAcquisition::getAcquiredAt)
                        .thenComparing(
                                InventoryAcquisition::getId, POSTGRES_UUID_ORDER))
                .toList();
    }

    private List<Integer> remainingByAge(Inventory inventory) {
        return lotsByAge(inventory).stream()
                .map(InventoryAcquisition::getQuantityRemaining)
                .toList();
    }

    private InventoryAcquisition onlyLot(Inventory inventory) {
        List<InventoryAcquisition> lots = lotsByAge(inventory);
        assertThat(lots).hasSize(1);
        return lots.get(0);
    }

    private Inventory reload(Inventory inventory) {
        return inventoryRepository.findById(inventory.getId()).orElseThrow();
    }

    private InventoryAcquisition seedLot(
            Workshop workshop, Inventory inventory, Supplier supplier,
            int units, String unitCost, java.time.LocalDateTime acquiredAt) {
        return acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(workshop)
                .inventory(inventory)
                .supplier(supplier)
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(AcquisitionType.NATIONAL)
                .quantityReceived(units)
                .quantityRemaining(units)
                .unitCost(new BigDecimal(unitCost))
                .acquiredAt(acquiredAt)
                .build());
    }

    private void bumpStock(Inventory inventory, int units) {
        Inventory managed = reload(inventory);
        managed.addStock(units);
        inventoryRepository.save(managed);
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

    private Supplier supplier(Workshop workshop, String name) {
        return supplierRepository.save(Supplier.builder()
                .workshop(workshop)
                .name(name + " " + unique())
                .build());
    }

    private UUID customer() {
        Customer customer = customerRepository.save(Customer.builder()
                .name("CLIENTE " + unique())
                .nit(String.valueOf(System.nanoTime()).substring(0, 9))
                .build());
        return customer.getId();
    }

    private Inventory inventory(
            Workshop workshop, FamilyCategory category, int stock) {
        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .build());
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name("PRODUCTO " + code)
                .attributes(new HashMap<>())
                .build());
        return inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("200.00"))
                .cost(new BigDecimal("1.00"))
                .stock(stock)
                .minStock(0)
                .build());
    }

}
