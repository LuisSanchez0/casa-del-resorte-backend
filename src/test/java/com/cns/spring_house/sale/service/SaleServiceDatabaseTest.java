package com.cns.spring_house.sale.service;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
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
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
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
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.RegisterPaymentRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleDetailWorkResultDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleActionType;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleDetailRepository;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workresult.dto.WorkResultSource;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.repository.WorkResultRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * Comportamiento de la venta con transacciones reales contra PostgreSQL.
 *
 * A diferencia de las pruebas unitarias del servicio, aqui NO hay una
 * transaccion de prueba que envuelva la llamada: la unica frontera transaccional
 * es la del propio `@Transactional` de SaleServiceImpl. Eso es lo que permite
 * comprobar de verdad que un fallo a mitad de una operacion no deja stock
 * descontado, items vendidos ni ventas a medio finalizar.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleServiceDatabaseTest {

    private static final String TEMPORARY_DATABASE = "sale_service_it";

    @Autowired
    private SaleService service;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private CustomerRepository customerRepository;

    /** NIT unico por venta abierta: la columna es UNIQUE. */
    private final AtomicLong customerSequence =
            new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private SaleDetailRepository detailRepository;

    @Autowired
    private WorkResultRepository workResultRepository;

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

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobLogRepository jobLogRepository;

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
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL. Mismo tope que ya
        // usan las demas pruebas de base de datos del proyecto.
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
     * Cuenta autenticada ya persistida por taller. El JWT subject SIEMPRE debe
     * ser un {@code UserAccount.id} real: {@code CurrentUserService} resuelve
     * la identidad autenticada consultando la base de datos por ese id
     * ({@code UserAccountRepository.findById}), no acepta un UUID inventado.
     * Guardar la cuenta por taller permite volver a autenticarse contra el
     * mismo taller (ver {@link #authenticateAgainst(Workshop)}) sin crear una
     * cuenta nueva cada vez.
     */
    private final Map<UUID, UserAccount> authenticatedAccounts = new HashMap<>();

    /** Cada prueba trabaja sobre su propio taller y se autentica contra el. */
    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        authenticateAgainst(workshop);
        return workshop;
    }

    /**
     * Autentica el contexto de seguridad contra un {@code UserAccount} real,
     * persistido con su {@code Employee} y su {@code Role}, perteneciente al
     * taller recibido. La primera vez que se autentica contra un taller se
     * crea la cuenta; las siguientes reutilizan la misma, para que un test
     * pueda "volver" a autenticarse contra un taller ya visitado sin duplicar
     * cuentas ni romper la unicidad de empleado/usuario.
     */
    private void authenticateAgainst(Workshop workshop) {
        UserAccount account = authenticatedAccounts.computeIfAbsent(
                workshop.getId(), workshopId -> persistAuthenticatedUser(workshop));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /**
     * Persiste el usuario que quedara autenticado: un {@code Employee} propio
     * (el "actor" que cancela/revierte, distinto del empleado que abrio cada
     * venta) mas la cuenta que lo vincula a un rol real, sembrado por Flyway
     * (V4__seed_default_roles.sql).
     */
    private UserAccount persistAuthenticatedUser(Workshop workshop) {
        Employee actor = employee(workshop);
        Role sellerRole = roleRepository.findAll().stream()
                .filter(role -> "SELLER".equals(role.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El rol SELLER no esta sembrado en la base de datos de prueba"));
        return userAccountRepository.save(UserAccount.builder()
                .employee(actor)
                .role(sellerRole)
                .username("actor_" + unique())
                .password("no-usada-en-pruebas")
                .isActive(true)
                .build());
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

    /**
     * Inventario con existencias Y con el LOTE que las respalda.
     *
     * Las dos cosas van juntas desde que el costo es historico: sembrar stock
     * sin lote deja un inventario que el FIFO no puede costear, y vender de el
     * falla —correctamente— con un error de integridad. Que este helper sea el
     * unico sitio donde nace un inventario es lo que garantiza que ninguna
     * prueba se olvide del lote.
     *
     * El costo del lote es el mismo `10.00` que el costo de referencia, de modo
     * que las comprobaciones de piso de precio siguen midiendo lo que median.
     */
    private Inventory inventory(Workshop workshop, Product product, int stock) {
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("100.00"))
                .cost(new BigDecimal("10.00"))
                .stock(stock)
                .minStock(0)
                .build());

        if (stock > 0) {
            purchaseLot(inventory, stock, new BigDecimal("10.00"));
        }

        return inventory;
    }

    /** Lote de COMPRA que respalda `units` existencias al costo indicado. */
    private InventoryAcquisition purchaseLot(
            Inventory inventory, int units, BigDecimal unitCost) {
        return acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(inventory.getWorkshop())
                .inventory(inventory)
                .supplier(supplier(inventory.getWorkshop()))
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(AcquisitionType.NATIONAL)
                .quantityReceived(units)
                .quantityRemaining(units)
                .unitCost(unitCost)
                .acquiredAt(LocalDateTime.now())
                .build());
    }

    /** Un proveedor por taller, reutilizado: el nombre es unico por taller. */
    private final Map<UUID, Supplier> suppliers = new HashMap<>();

    private Supplier supplier(Workshop workshop) {
        return suppliers.computeIfAbsent(workshop.getId(), ignored ->
                supplierRepository.save(Supplier.builder()
                        .workshop(workshop)
                        .name("PROVEEDOR " + unique())
                        .build()));
    }

    private InventoryItem availableItem(Inventory inventory) {
        return itemRepository.save(InventoryItem.builder()
                .inventory(inventory)
                .status(InventoryItemStatus.AVAILABLE)
                .build());
    }

    /**
     * Venta abierta con NIT, como toda venta que la caja abria antes de V40:
     * las pruebas de esta clase miden stock, trabajos y cobro, que no dependen
     * de la identidad fiscal; esa vive en SaleTaxModeDatabaseTest.
     */
    private Sale openSale(Workshop workshop) {
        return saleRepository.save(Sale.builder()
                .customer(customerRepository.save(Customer.builder()
                        .nit(String.valueOf(1_000_000_000L + customerSequence.incrementAndGet()))
                        .name("CLIENTE " + unique())
                        .build()))
                .workshop(workshop)
                .employee(employee(workshop))
                .build());
    }

    /** Servicio vendible: el handler de JOB lo resuelve por producto y taller. */
    private Job job(Workshop workshop, Product jobProduct) {
        return jobRepository.save(Job.builder()
                .product(jobProduct)
                .workshop(workshop)
                .price(new BigDecimal("150.00"))
                .build());
    }

    /** Trabajo terminado pendiente de venta, con origen en un servicio. */
    private WorkResult pendingJobWorkResult(Workshop workshop, Product jobProduct) {
        Job job = job(workshop, jobProduct);
        JobLog jobLog = jobLogRepository.save(JobLog.builder()
                .employee(employee(workshop))
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        return workResultRepository.save(WorkResult.builder()
                .workshop(workshop)
                .jobLog(jobLog)
                .build());
    }

    private WorkResultStatus statusOf(WorkResult workResult) {
        return workResultRepository.findById(workResult.getId())
                .orElseThrow()
                .getStatus();
    }

    private Sale reload(Sale sale) {
        return saleRepository.findById(sale.getId()).orElseThrow();
    }

    /** El empleado detras de la cuenta autenticada contra ese taller. */
    private Employee authenticatedActor(Workshop workshop) {
        return authenticatedAccounts.get(workshop.getId()).getEmployee();
    }

    /** Ids de la cartera completa, sin paginar, para asertar pertenencia. */
    private List<UUID> portfolio(PaymentStatus paymentStatus) {
        return service.getExchangeInvoices(paymentStatus, 0, 100)
                .getContent().stream()
                .map(ExchangeInvoiceSummaryDTO::id)
                .toList();
    }

    /**
     * Transformacion terminada y sin vender: una unidad fisica de salida con su
     * WorkResult. No tiene JobLog, y esa ausencia ES lo que la distingue de un
     * servicio ejecutado.
     */
    private WorkResult pendingTransformationWorkResult(
            Workshop workshop, InventoryItem outputItem) {
        return workResultRepository.save(WorkResult.builder()
                .workshop(workshop)
                .item(outputItem)
                .build());
    }

    private SaleDetailResponseDTO onlyDetailOf(SaleResponseDTO sale) {
        assertThat(sale.details()).hasSize(1);
        return sale.details().get(0);
    }

    // ── Origen del trabajo en la venta leida ─────────────────────

    /**
     * Lo que la bitacora necesita saber de una transformacion vendida, leido de
     * la venta ya emitida y sin una sola consulta extra.
     *
     * La linea guarda el producto de SALIDA de la transformacion —un producto
     * de catalogo normal y corriente— y, aparte, el texto fiscal que el cajero
     * escribio. Los dos viajan, y son cosas distintas: el nombre canonico es lo
     * unico que el catalogo puede validar despues; el texto fiscal es lo que la
     * secretaria lee en el papel que tiene en la mano.
     */
    @Test
    void aSoldTransformationTravelsWithItsOriginInTheSaleResponse() {
        Workshop workshop = authenticatedWorkshop();
        Product outputProduct = product(FamilyCategory.SPECIAL);
        InventoryItem outputItem = availableItem(
                inventory(workshop, outputProduct, 1));
        WorkResult workResult = pendingTransformationWorkResult(
                workshop, outputItem);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                outputProduct.getId(), outputItem.getId(), workResult.getId(),
                null, new BigDecimal("380.00"), "AMORTIGUADOR RECTIFICADO"));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));

        SaleDetailResponseDTO detail = onlyDetailOf(service.get(sale.getId()));

        assertThat(detail.workResult())
                .isEqualTo(new SaleDetailWorkResultDTO(
                        workResult.getId(), WorkResultSource.TRANSFORMATION));
        // El nombre canonico del catalogo, que es lo unico que la bitacora
        // puede volver a validar; el texto fiscal es otro campo.
        assertThat(detail.product().name()).isEqualTo(outputProduct.getName());
        assertThat(detail.invoiceDescription())
                .isEqualTo("AMORTIGUADOR RECTIFICADO")
                .isNotEqualTo(detail.product().name());
        assertThat(detail.price()).isEqualByComparingTo("380.00");
        assertThat(detail.quantity()).isEqualTo(1);
        assertThat(detail.subTotal()).isEqualByComparingTo("380.00");
    }

    /** Un servicio ejecutado viaja por el mismo campo, pero como JOB. */
    @Test
    void aSoldJobTravelsAsJobAndNotAsTransformation() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 2, null, null));

        SaleDetailResponseDTO detail = onlyDetailOf(service.get(sale.getId()));

        assertThat(detail.workResult())
                .isEqualTo(new SaleDetailWorkResultDTO(
                        workResult.getId(), WorkResultSource.JOB));
    }

    /** Y una venta corriente sigue sin trabajo detras: el campo llega en null. */
    @Test
    void anOrdinarySaleLineHasNoWorkResult() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        inventory(workshop, normalProduct, 5);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 2, null, null));

        assertThat(onlyDetailOf(service.get(sale.getId())).workResult()).isNull();
    }

    /**
     * Revertir la venta desvincula el trabajo, y la lectura tiene que contarlo
     * igual: la linea historica sigue ahi, pero ya no dice que la respalde un
     * trabajo que volvio al pool.
     */
    @Test
    void reversingTheSaleLeavesTheLineWithoutOrigin() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));
        assertThat(onlyDetailOf(service.get(sale.getId())).workResult())
                .isNotNull();

        service.reverse(sale.getId(), new ReverseSaleRequestDTO("Error de cobro"));

        assertThat(onlyDetailOf(service.get(sale.getId())).workResult()).isNull();
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);
    }

    // ── WorkResult liberado sin completar la venta ───────────────

    /**
     * Quitar el detalle devuelve el trabajo al pool: nunca llego a venderse, asi
     * que sigue PENDING y vuelve a aparecer como disponible para otra venta.
     */
    @Test
    void removingADetailReleasesItsWorkResultForAnotherSale() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));

        UUID detailId = reload(sale).getDetails().get(0).getId();
        assertThat(detailRepository.existsByWorkResultId(workResult.getId()))
                .isTrue();
        // Reservado, todavia no vendido.
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);

        service.removeDetail(detailId);

        assertThat(detailRepository.findById(detailId)).isEmpty();
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);
        assertThat(detailRepository.existsByWorkResultId(workResult.getId()))
                .isFalse();
        assertThat(workResultRepository.findAvailableByWorkshopIdAndStatus(
                workshop.getId(), WorkResultStatus.PENDING))
                .extracting(WorkResult::getId)
                .contains(workResult.getId());
    }

    /** Y puede reservarse otra vez, en una venta distinta. */
    @Test
    void aReleasedWorkResultCanBeAddedToAnotherSale() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale first = openSale(workshop);
        Sale second = openSale(workshop);
        AddSaleDetailRequestDTO requestDTO = new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null);

        service.addDetail(first.getId(), requestDTO);
        service.removeDetail(reload(first).getDetails().get(0).getId());
        service.addDetail(second.getId(), requestDTO);

        assertThat(reload(second).getDetails())
                .singleElement()
                .extracting(detail -> detail.getWorkResult().getId())
                .isEqualTo(workResult.getId());
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);
    }

    /**
     * Eliminar la venta abierta libera igual: el trabajo nunca se vendio porque
     * el documento nunca se emitio.
     */
    @Test
    void deletingAnOpenSaleLeavesItsWorkResultPendingAndAvailable() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));

        service.delete(sale.getId());

        assertThat(saleRepository.findById(sale.getId())).isEmpty();
        assertThat(workResultRepository.findById(workResult.getId()))
                .isPresent();
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);
        assertThat(workResultRepository.findAvailableByWorkshopIdAndStatus(
                workshop.getId(), WorkResultStatus.PENDING))
                .extracting(WorkResult::getId)
                .contains(workResult.getId());
    }

    /** Eliminar la venta abierta tambien devuelve el stock que habia reservado. */
    @Test
    void deletingAnOpenSaleReturnsTheReservedStock() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        Inventory stock = inventory(workshop, normalProduct, 10);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 3, null, null));

        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(7);

        service.delete(sale.getId());

        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(10);
    }

    // ── Atomicidad ───────────────────────────────────────────────

    /**
     * El handler descuenta stock y marca el item como vendido ANTES de reservar
     * el WorkResult. Si la reserva falla, la transaccion completa tiene que
     * revertirse: sin eso, cada intento fallido consumia una unidad real.
     */
    @Test
    void aFailedWorkResultReservationRollsBackTheWholeDetail() {
        Workshop workshop = authenticatedWorkshop();
        Product specialProduct = product(FamilyCategory.SPECIAL);
        Inventory stock = inventory(workshop, specialProduct, 1);
        InventoryItem item = availableItem(stock);
        // WorkResult valido y PENDING, pero de OTRO item: la validacion de origen
        // lo rechaza despues de que el handler ya movio el inventario.
        WorkResult foreignWorkResult = workResultRepository.save(
                WorkResult.builder()
                        .workshop(workshop)
                        .item(availableItem(stock))
                        .build());
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(),
                new AddSaleDetailRequestDTO(
                        specialProduct.getId(), item.getId(),
                        foreignWorkResult.getId(), 1, null, null)))
                .isInstanceOf(ValidationException.class);

        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock())
                .as("el stock descontado por el handler vuelve atras")
                .isEqualTo(1);
        assertThat(itemRepository.findById(item.getId())
                .orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(reload(sale).getDetails()).isEmpty();
        assertThat(reload(sale).getTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(statusOf(foreignWorkResult))
                .isEqualTo(WorkResultStatus.PENDING);
    }

    /**
     * Si al finalizar falla el marcado de uno de los trabajos, no puede quedar
     * una venta "medio emitida": ni estado, ni fechas, ni el resto de WorkResults.
     */
    @Test
    void aFailedMarkAsSoldRollsBackTheWholeCompletion() {
        Workshop workshop = authenticatedWorkshop();
        Product firstProduct = product(FamilyCategory.JOB);
        Product secondProduct = product(FamilyCategory.JOB);
        WorkResult healthy = pendingJobWorkResult(workshop, firstProduct);
        WorkResult alreadySold = pendingJobWorkResult(workshop, secondProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                firstProduct.getId(), null, healthy.getId(), 1, null, null));
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                secondProduct.getId(), null, alreadySold.getId(), 1, null, null));

        // Otra venta se lo llevo primero: markAsSold ya no lo encuentra PENDING.
        WorkResult stolen = workResultRepository.findById(alreadySold.getId())
                .orElseThrow();
        stolen.setStatus(WorkResultStatus.SOLD);
        workResultRepository.save(stolen);

        assertThatThrownBy(() -> service.completeSale(
                sale.getId(),
                new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class);

        Sale stored = reload(sale);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(stored.getInvoiceType()).isNull();
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getCompletedAt()).isNull();
        assertThat(stored.getPaidAt()).isNull();
        // El trabajo sano no puede quedarse vendido por una venta que no ocurrio.
        assertThat(statusOf(healthy)).isEqualTo(WorkResultStatus.PENDING);
    }

    // ── Cambiaria completa: emitir, listar y cobrar ──────────────

    /**
     * Recorrido real de una cambiaria: se emite sin cobrar, el trabajo queda
     * vendido igualmente, aparece en la cartera pendiente y al cobrarla solo
     * cambia la dimension de pago.
     */
    @Test
    void anExchangeInvoiceIsIssuedListedAndPaidWithoutTouchingItsWorkResult() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));

        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null));

        Sale issued = reload(sale);
        LocalDateTime issuedAt = issued.getCompletedAt();
        assertThat(issued.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(issued.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(issued.getPaidAt()).isNull();
        // Vendido aunque siga sin cobrarse.
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.SOLD);
        assertThat(portfolio(PaymentStatus.PENDING)).contains(sale.getId());

        service.registerPayment(sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER));

        Sale paid = reload(sale);
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(paid.getCompletedAt())
                .as("emitir y cobrar son eventos distintos")
                .isEqualTo(issuedAt);
        assertThat(paid.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.SOLD);
        assertThat(portfolio(PaymentStatus.PENDING)).doesNotContain(sale.getId());
        assertThat(portfolio(PaymentStatus.PAID)).contains(sale.getId());
    }

    /**
     * Sin filtro entra toda la cartera cambiaria del taller del token —cobrada y
     * pendiente— y solo esa: ni la factura normal, ni la de otro taller.
     */
    @Test
    void theExchangeInvoicePortfolioIsScopedToTheAuthenticatedWorkshop() {
        Workshop mine = authenticatedWorkshop();
        Sale ownPending = issuedExchangeInvoice(mine);
        Sale ownCollected = collectedExchangeInvoice(mine);
        Sale ownInvoice = issuedNormalInvoice(mine);

        Workshop foreign = authenticatedWorkshop();
        Sale foreignExchange = issuedExchangeInvoice(foreign);

        authenticateAgainst(mine);
        List<UUID> visible = portfolio(null);

        assertThat(visible).contains(ownPending.getId(), ownCollected.getId());
        assertThat(visible).doesNotContain(ownInvoice.getId());
        assertThat(visible).doesNotContain(foreignExchange.getId());
    }

    /** El filtro tampoco se escapa del taller del token. */
    @Test
    void theFilteredPortfolioIsScopedToTheAuthenticatedWorkshopToo() {
        Workshop mine = authenticatedWorkshop();
        Sale ownPending = issuedExchangeInvoice(mine);
        Sale ownCollected = collectedExchangeInvoice(mine);

        Workshop foreign = authenticatedWorkshop();
        Sale foreignPending = issuedExchangeInvoice(foreign);

        authenticateAgainst(mine);

        assertThat(portfolio(PaymentStatus.PENDING))
                .contains(ownPending.getId())
                .doesNotContain(ownCollected.getId(), foreignPending.getId());
        assertThat(portfolio(PaymentStatus.PAID))
                .contains(ownCollected.getId())
                .doesNotContain(ownPending.getId());
    }

    /** De la mas reciente a la mas antigua, ordenado por la base de datos. */
    @Test
    void theExchangeInvoicePortfolioComesNewestFirst() {
        Workshop workshop = authenticatedWorkshop();
        LocalDateTime now = LocalDateTime.now();
        Sale oldest = issuedExchangeInvoice(workshop, now.minusDays(10));
        Sale newest = issuedExchangeInvoice(workshop, now.minusDays(1));
        Sale middle = issuedExchangeInvoice(workshop, now.minusDays(5));

        assertThat(portfolio(null))
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    // ── Cancelacion ──────────────────────────────────────────────

    /**
     * Cancelar conserva la venta: a diferencia de borrarla, queda constancia de
     * que existio, con su cliente, su placa y el total que llevaba.
     */
    @Test
    void cancellingKeepsTheSaleAsHistory() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        Inventory stock = inventory(workshop, normalProduct, 10);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 3, new BigDecimal("50.00"), null));

        service.cancel(sale.getId(), new CancelSaleRequestDTO("Cliente desistio de la compra"));

        Sale stored = reload(sale);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(stored.getWorkshop().getId()).isEqualTo(workshop.getId());
        assertThat(stored.getEmployee()).isNotNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        // El motivo, el autor y el tipo de accion quedan persistidos.
        assertThat(stored.getAction()).isNotNull();
        assertThat(stored.getAction().getActionType())
                .isEqualTo(SaleActionType.CANCEL_PENDING);
        assertThat(stored.getAction().getReason())
                .isEqualTo("Cliente desistio de la compra");
        // El autor es la cuenta autenticada del taller, no cualquier empleado.
        assertThat(stored.getAction().getPerformedBy())
                .isEqualTo(authenticatedActor(workshop));
        assertThat(stored.getAction().getCreatedAt()).isNotNull();
        // El total que llevaba antes de anularse se conserva.
        assertThat(stored.getTotal()).isEqualByComparingTo("150.00");
        // Las lineas sobreviven como snapshot...
        assertThat(stored.getDetails()).singleElement()
                .satisfies(detail -> {
                    assertThat(detail.getQuantity()).isEqualTo(3);
                    assertThat(detail.getPrice()).isEqualByComparingTo("50.00");
                    assertThat(detail.getProduct().getId())
                            .isEqualTo(normalProduct.getId());
                });
        // ...pero sin seguir reservando nada.
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(10);
    }

    @Test
    void cancellingReturnsASpecialItemToTheShelf() {
        Workshop workshop = authenticatedWorkshop();
        Product specialProduct = product(FamilyCategory.SPECIAL);
        Inventory stock = inventory(workshop, specialProduct, 1);
        InventoryItem item = availableItem(stock);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                specialProduct.getId(), item.getId(), null, 1, null, null));

        service.cancel(sale.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        assertThat(itemRepository.findById(item.getId())
                .orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(1);
    }

    /** Un servicio no reserva inventario: cancelar no debe inventar movimiento. */
    @Test
    void cancellingAJobSaleTouchesNoInventory() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        job(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, null, 2, null, null));

        service.cancel(sale.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        assertThat(reload(sale).getStatus()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(reload(sale).getDetails()).hasSize(1);
    }

    /**
     * El trabajo nunca se vendio: sigue PENDING y vuelve a estar disponible para
     * otra venta, aunque la linea cancelada siga existiendo.
     */
    @Test
    void cancellingReleasesItsWorkResultForAnotherSale() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale cancelled = openSale(workshop);
        service.addDetail(cancelled.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));

        service.cancel(cancelled.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);
        assertThat(detailRepository.existsByWorkResultId(workResult.getId()))
                .isFalse();
        assertThat(workResultRepository.findAvailableByWorkshopIdAndStatus(
                workshop.getId(), WorkResultStatus.PENDING))
                .extracting(WorkResult::getId)
                .contains(workResult.getId());
        // La linea cancelada sigue ahi, ya sin reservar el trabajo.
        assertThat(reload(cancelled).getDetails()).singleElement()
                .satisfies(detail -> assertThat(detail.getWorkResult()).isNull());

        // Y el trabajo se puede vender de verdad en otra venta.
        Sale second = openSale(workshop);
        service.addDetail(second.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));
        service.completeSale(second.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));

        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.SOLD);
    }

    /** Una venta cancelada es historial: ya no se toca. */
    @Test
    void aCancelledSaleIsNoLongerEditable() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        job(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, null, 1, null, null));
        UUID detailId = reload(sale).getDetails().get(0).getId();
        service.cancel(sale.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(), new AddSaleDetailRequestDTO(
                        jobProduct.getId(), null, null, 1, null, null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.removeDetail(detailId))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.completeSale(
                sale.getId(), new CompleteSaleRequestDTO(
                        InvoiceType.INVOICE, PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.registerPayment(
                sale.getId(), new RegisterPaymentRequestDTO(PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.cancel(
                sale.getId(), new CancelSaleRequestDTO("Motivo de prueba")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La venta ya se encuentra cancelada");

        assertThat(reload(sale).getStatus()).isEqualTo(SaleStatus.CANCELLED);
    }

    /** Una factura emitida no se anula cambiando el enum. */
    @Test
    void anIssuedInvoiceCannotBeCancelled() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = issuedExchangeInvoice(workshop);

        assertThatThrownBy(() -> service.cancel(
                sale.getId(), new CancelSaleRequestDTO("Motivo de prueba")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo se puede cancelar una venta abierta");

        Sale stored = reload(sale);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getInvoiceType())
                .isEqualTo(InvoiceType.EXCHANGE_INVOICE);
    }

    /**
     * Si la restauracion de una linea falla, la cancelacion entera se revierte:
     * ni media venta cancelada ni medio inventario devuelto.
     */
    @Test
    void aFailedRestorationRollsBackTheWholeCancellation() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        Product specialProduct = product(FamilyCategory.SPECIAL);
        Inventory normalStock = inventory(workshop, normalProduct, 10);
        Inventory specialStock = inventory(workshop, specialProduct, 1);
        InventoryItem item = availableItem(specialStock);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 3, null, null));
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                specialProduct.getId(), item.getId(), null, 1, null, null));

        // Alguien devolvio la unidad por otra via: el handler de SPECIAL exige
        // encontrarla vendida y aborta la cancelacion.
        InventoryItem touched = itemRepository.findById(item.getId()).orElseThrow();
        touched.setStatus(InventoryItemStatus.AVAILABLE);
        itemRepository.save(touched);

        assertThatThrownBy(() -> service.cancel(
                sale.getId(), new CancelSaleRequestDTO("Motivo de prueba")))
                .isInstanceOf(ValidationException.class);

        Sale stored = reload(sale);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(stored.getDetails()).hasSize(2);
        // El stock del detalle NORMAL no puede haberse devuelto a medias.
        assertThat(inventoryRepository.findById(normalStock.getId())
                .orElseThrow().getStock()).isEqualTo(7);
    }

    /** DELETE hace desaparecer el borrador; CANCEL lo conserva anulado. */
    @Test
    void deleteRemovesTheDraftWhileCancelKeepsIt() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        Inventory stock = inventory(workshop, normalProduct, 10);
        AddSaleDetailRequestDTO line = new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 2, null, null);

        Sale deleted = openSale(workshop);
        service.addDetail(deleted.getId(), line);
        // Sin accion todavia: borrar no debe necesitar ni generar una.
        assertThat(reload(deleted).getAction()).isNull();
        service.delete(deleted.getId());

        Sale cancelled = openSale(workshop);
        service.addDetail(cancelled.getId(), line);
        service.cancel(cancelled.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        // El borrador desaparece por completo: ni la venta ni una accion falsa
        // de cancelacion/reversion quedan asociadas a el.
        assertThat(saleRepository.findById(deleted.getId())).isEmpty();
        assertThat(saleRepository.findById(cancelled.getId())).isPresent();
        assertThat(reload(cancelled).getStatus()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(reload(cancelled).getAction()).isNotNull();
        assertThat(reload(cancelled).getAction().getActionType())
                .isEqualTo(SaleActionType.CANCEL_PENDING);
        // Los dos caminos devuelven el stock igual.
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(10);
    }

    /** Una venta cancelada no aparece en la cartera cambiaria. */
    @Test
    void aCancelledSaleIsNotPartOfThePortfolio() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        job(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, null, 1, null, null));

        service.cancel(sale.getId(), new CancelSaleRequestDTO("Motivo de prueba"));

        assertThat(portfolio(null)).doesNotContain(sale.getId());
    }

    // ── Reversion de una venta completada ─────────────────────────

    /** Revertir una factura normal ya cobrada restaura el stock y deja historial. */
    @Test
    void reversingACompletedInvoiceRestoresStockAndKeepsHistory() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        Inventory stock = inventory(workshop, normalProduct, 10);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 4, null, null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(6);

        service.reverse(sale.getId(),
                new ReverseSaleRequestDTO("Se facturo el producto equivocado"));

        Sale stored = reload(sale);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.REVERSED);
        assertThat(stored.getDetails()).hasSize(1);
        assertThat(stored.getTotal()).isNotNull();
        // No es un reembolso fiscal: el cobro ya registrado no se toca.
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(10);
        assertThat(stored.getAction()).isNotNull();
        assertThat(stored.getAction().getActionType())
                .isEqualTo(SaleActionType.REVERSE_COMPLETED);
        assertThat(stored.getAction().getReason())
                .isEqualTo("Se facturo el producto equivocado");
        // Misma cuenta autenticada que emitio la reversion.
        assertThat(stored.getAction().getPerformedBy())
                .isEqualTo(authenticatedActor(workshop));
        assertThat(stored.getAction().getCreatedAt()).isNotNull();
    }

    @Test
    void reversingASpecialItemSaleReturnsItToTheShelf() {
        Workshop workshop = authenticatedWorkshop();
        Product specialProduct = product(FamilyCategory.SPECIAL);
        Inventory stock = inventory(workshop, specialProduct, 1);
        InventoryItem item = availableItem(stock);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                specialProduct.getId(), item.getId(), null, 1, null, null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));

        service.reverse(sale.getId(), new ReverseSaleRequestDTO("Item defectuoso"));

        assertThat(itemRepository.findById(item.getId())
                .orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(1);
    }

    /**
     * El trabajo vendido vuelve a PENDING y a estar disponible para otra
     * venta: la reversion deshace exactamente lo que completar hizo.
     */
    @Test
    void reversingASoldJobReturnsItsWorkResultToPending() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));
        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.SOLD);

        service.reverse(sale.getId(), new ReverseSaleRequestDTO("Trabajo mal cobrado"));

        assertThat(statusOf(workResult)).isEqualTo(WorkResultStatus.PENDING);
        assertThat(detailRepository.existsByWorkResultId(workResult.getId()))
                .isFalse();
        assertThat(workResultRepository.findAvailableByWorkshopIdAndStatus(
                workshop.getId(), WorkResultStatus.PENDING))
                .extracting(WorkResult::getId)
                .contains(workResult.getId());
    }

    @Test
    void aPendingSaleCannotBeReversed() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.reverse(
                sale.getId(), new ReverseSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Una venta abierta se anula, no se revierte");
    }

    @Test
    void aCancelledSaleCannotBeReversed() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        inventory(workshop, normalProduct, 10);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 1, null, null));
        service.cancel(sale.getId(), new CancelSaleRequestDTO("Motivo"));

        assertThatThrownBy(() -> service.reverse(
                sale.getId(), new ReverseSaleRequestDTO("Motivo")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Una venta anulada no se puede revertir");
    }

    @Test
    void aSaleCannotBeReversedTwice() {
        Workshop workshop = authenticatedWorkshop();
        Product normalProduct = product(FamilyCategory.NORMAL);
        inventory(workshop, normalProduct, 10);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                normalProduct.getId(), null, null, 1, null, null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH));
        service.reverse(sale.getId(), new ReverseSaleRequestDTO("Primera reversion"));

        assertThatThrownBy(() -> service.reverse(
                sale.getId(), new ReverseSaleRequestDTO("Segunda reversion")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La venta ya fue revertida");
    }

    // ── Paginacion y resumen ─────────────────────────────────────

    /**
     * La cartera crece sin limite, asi que se sirve por paginas: el total y el
     * numero de paginas se calculan sobre TODA la cartera, no sobre lo devuelto.
     */
    @Test
    void thePortfolioIsServedInPages() {
        Workshop workshop = authenticatedWorkshop();
        LocalDateTime now = LocalDateTime.now();
        List<UUID> newestFirst = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            newestFirst.add(issuedExchangeInvoice(
                    workshop, now.minusDays(index)).getId());
        }

        Page<ExchangeInvoiceSummaryDTO> first =
                service.getExchangeInvoices(null, 0, 2);
        Page<ExchangeInvoiceSummaryDTO> second =
                service.getExchangeInvoices(null, 1, 2);
        Page<ExchangeInvoiceSummaryDTO> last =
                service.getExchangeInvoices(null, 2, 2);

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.getNumber()).isZero();
        assertThat(first.isFirst()).isTrue();
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(2);
        assertThat(last.getContent()).hasSize(1);
        assertThat(last.isLast()).isTrue();

        // Las tres paginas recorren la cartera entera, en orden y sin repetir.
        assertThat(Stream.of(first, second, last)
                .flatMap(page -> page.getContent().stream())
                .map(ExchangeInvoiceSummaryDTO::id))
                .containsExactlyElementsOf(newestFirst);
    }

    /** El filtro se aplica antes de paginar: el total es el de la cartera filtrada. */
    @Test
    void theFilterIsAppliedBeforePaging() {
        Workshop workshop = authenticatedWorkshop();
        issuedExchangeInvoice(workshop);
        issuedExchangeInvoice(workshop);
        collectedExchangeInvoice(workshop);

        assertThat(service.getExchangeInvoices(null, 0, 10).getTotalElements())
                .isEqualTo(3);
        assertThat(service.getExchangeInvoices(PaymentStatus.PENDING, 0, 10)
                .getTotalElements()).isEqualTo(2);
        assertThat(service.getExchangeInvoices(PaymentStatus.PAID, 0, 10)
                .getTotalElements()).isEqualTo(1);
    }

    /**
     * Dos documentos emitidos en el mismo instante siguen saliendo en un orden
     * fijo: sin el desempate, la paginacion podia repetir una fila y perder otra.
     */
    @Test
    void theOrderIsStableWhenTwoInvoicesShareTheIssueDate() {
        Workshop workshop = authenticatedWorkshop();
        LocalDateTime sameInstant = LocalDateTime.now().minusHours(3);
        issuedExchangeInvoice(workshop, sameInstant);
        issuedExchangeInvoice(workshop, sameInstant);
        issuedExchangeInvoice(workshop, sameInstant);

        List<UUID> firstRead = portfolio(null);
        List<UUID> secondRead = portfolio(null);

        assertThat(firstRead).containsExactlyElementsOf(secondRead);
        assertThat(service.getExchangeInvoices(null, 0, 2).getContent())
                .extracting(ExchangeInvoiceSummaryDTO::id)
                .containsExactly(firstRead.get(0), firstRead.get(1));
        assertThat(service.getExchangeInvoices(null, 1, 2).getContent())
                .extracting(ExchangeInvoiceSummaryDTO::id)
                .containsExactly(firstRead.get(2));
    }

    /** El resumen trae lo que la pantalla de caja necesita, ya cobrado o no. */
    @Test
    void theSummaryCarriesWhatTheDeskNeeds() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        job(workshop, jobProduct);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, null, 1, new BigDecimal("1250.00"), null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null));

        ExchangeInvoiceSummaryDTO summary =
                service.getExchangeInvoices(null, 0, 10).getContent().get(0);

        assertThat(summary.id()).isEqualTo(sale.getId());
        assertThat(summary.invoiceType()).isEqualTo(InvoiceType.EXCHANGE_INVOICE);
        assertThat(summary.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(summary.paymentMethod()).isNull();
        assertThat(summary.paidAt()).isNull();
        assertThat(summary.completedAt()).isNotNull();
        assertThat(summary.total()).isEqualByComparingTo("1250.00");
    }

    /** Una venta sin cliente no rompe el resumen: llega con customer nulo. */
    @Test
    void aSaleWithoutCustomerIsSummarizedWithoutOne() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = issuedExchangeInvoice(workshop);

        ExchangeInvoiceSummaryDTO summary =
                service.getExchangeInvoices(null, 0, 10).getContent().get(0);

        assertThat(summary.id()).isEqualTo(sale.getId());
        assertThat(summary.customer()).isNull();
    }

    @Test
    void anAbsurdPageSizeIsRejected() {
        authenticatedWorkshop();

        assertThatThrownBy(() -> service.getExchangeInvoices(null, 0, 5000))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getExchangeInvoices(null, 0, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getExchangeInvoices(null, -1, 20))
                .isInstanceOf(ValidationException.class);
    }

    private Sale issuedExchangeInvoice(Workshop workshop) {
        return issuedExchangeInvoice(workshop, LocalDateTime.now());
    }

    private Sale issuedExchangeInvoice(Workshop workshop, LocalDateTime issuedAt) {
        return saleRepository.save(Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(issuedAt)
                .build());
    }

    /** Cambiaria ya cobrada: emitida un dia y pagada mas tarde. */
    private Sale collectedExchangeInvoice(Workshop workshop) {
        LocalDateTime issuedAt = LocalDateTime.now().minusDays(20);
        return saleRepository.save(Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .paymentMethod(PaymentMethod.TRANSFER)
                .completedAt(issuedAt)
                .paidAt(issuedAt.plusDays(5))
                .build());
    }

    private Sale issuedNormalInvoice(Workshop workshop) {
        LocalDateTime now = LocalDateTime.now();
        return saleRepository.save(Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .paymentMethod(PaymentMethod.CASH)
                .completedAt(now)
                .paidAt(now)
                .build());
    }

    // ── Reglas de emision y cobro ────────────────────────────────

    /** Un documento sin lineas no se emite. */
    @Test
    void anEmptySaleCannotBeCompleted() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.completeSale(
                sale.getId(),
                new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se puede completar una venta sin detalles");

        Sale stored = reload(sale);
        assertThat(stored.getStatus()).isEqualTo(SaleStatus.PENDING);
        assertThat(stored.getInvoiceType()).isNull();
        assertThat(stored.getCompletedAt()).isNull();
        assertThat(stored.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stored.getPaidAt()).isNull();
    }

    /** Una factura normal ya se cobro al emitirse: no hay cobro posterior. */
    @Test
    void aNormalInvoiceCannotRegisterALaterPayment() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = issuedNormalInvoice(workshop);

        assertThatThrownBy(() -> service.registerPayment(
                sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.TRANSFER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(
                        "Solo las facturas cambiarias pueden registrar un pago posterior");

        Sale stored = reload(sale);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
    }

    /** Registrar el pago no toca ni el stock ni los items de la venta. */
    @Test
    void registeringThePaymentLeavesTheInventoryUntouched() {
        Workshop workshop = authenticatedWorkshop();
        Product specialProduct = product(FamilyCategory.SPECIAL);
        Inventory stock = inventory(workshop, specialProduct, 1);
        InventoryItem item = availableItem(stock);
        Sale sale = openSale(workshop);
        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                specialProduct.getId(), item.getId(), null, 1, null, null));
        service.completeSale(sale.getId(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null));

        List<SaleDetail> detailsBefore = reload(sale).getDetails();

        service.registerPayment(sale.getId(),
                new RegisterPaymentRequestDTO(PaymentMethod.CASH));

        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isZero();
        assertThat(itemRepository.findById(item.getId())
                .orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.SOLD);
        assertThat(reload(sale).getDetails())
                .extracting(SaleDetail::getId)
                .containsExactlyElementsOf(
                        detailsBefore.stream().map(SaleDetail::getId).toList());
    }

    // ── Precio de un producto sin precio asignado ────────────────
    //
    // `inventory.price` es `NUMERIC(10,2) NOT NULL CHECK (>= 0)`: el esquema no
    // admite nulo, asi que el CERO es la unica forma de decir "sin precio
    // asignado". Fijarlo durante una venta escribe DOS cosas —la linea y el
    // precio vigente del taller— y las dos tienen que ir juntas: antes eran dos
    // peticiones HTTP seguidas, y entre una y otra cabia una venta cobrada a un
    // precio que el catalogo nunca llego a aprender.

    /** Un inventario sin precio asignado, opcionalmente sin lote que lo respalde. */
    private Inventory unpricedInventory(
            Workshop workshop, Product product, int stock, boolean withLot) {
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(BigDecimal.ZERO)
                .cost(new BigDecimal("10.00"))
                .stock(stock)
                .minStock(0)
                .build());

        if (withLot && stock > 0) {
            purchaseLot(inventory, stock, new BigDecimal("10.00"));
        }

        return inventory;
    }

    private BigDecimal catalogPriceOf(Inventory inventory) {
        return inventoryRepository.findById(inventory.getId())
                .orElseThrow()
                .getPrice();
    }

    @Test
    void aManualPriceOnAnUnpricedProductIsStoredInTheSaleAndInTheCatalog() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = unpricedInventory(workshop, product, 10, true);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                product.getId(), null, null, 2, new BigDecimal("250.50"), null));

        // La linea cobra lo que el cajero indico...
        assertThat(reload(sale).getDetails())
                .singleElement()
                .satisfies(detail -> {
                    assertThat(detail.getPrice()).isEqualByComparingTo("250.50");
                    assertThat(detail.getSubTotal()).isEqualByComparingTo("501.00");
                });

        // ...y el taller aprende ese precio para las proximas ventas, en la
        // MISMA transaccion: no hay un instante en el que exista una sin el otro.
        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("250.50");
    }

    /**
     * Sin precio no hay venta: dejarlo vacio hacia que el precio efectivo cayera
     * en el cero del catalogo y la pieza se despachara gratis.
     */
    @Test
    void anUnpricedProductCannotBeSoldWithoutAManualPrice() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = unpricedInventory(workshop, product, 10, true);
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(), new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 1, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un precio de venta asignado");

        assertThat(reload(sale).getDetails()).isEmpty();
        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("0.00");
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(10);
    }

    /** Un cero explicito es lo mismo que no indicar nada: no es un precio. */
    @Test
    void anUnpricedProductCannotBeSoldAtZero() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = unpricedInventory(workshop, product, 10, true);
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(), new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 1, BigDecimal.ZERO, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un precio de venta asignado");

        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("0.00");
    }

    /**
     * Si la linea se rechaza, el catalogo no se entera. Es la mitad facil de la
     * atomicidad: la validacion ocurre antes de escribir nada.
     */
    @Test
    void aRejectedLineLeavesTheCatalogPriceUntouched() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = unpricedInventory(workshop, product, 10, true);
        Sale sale = openSale(workshop);

        // Por debajo del costo de adquisicion (10.00).
        assertThatThrownBy(() -> service.addDetail(
                sale.getId(), new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 1, new BigDecimal("5.00"), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("costo de adquisicion");

        assertThat(reload(sale).getDetails()).isEmpty();
        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("0.00");
    }

    /**
     * La otra mitad, y la que de verdad prueba la transaccion: un fallo POSTERIOR
     * a haber fijado el precio y a haber persistido la linea.
     *
     * Se provoca con un inventario que tiene existencias pero NINGUN lote que
     * las respalde: el precio ya se asigno, el `SaleDetail` ya se guardo con
     * `saveAndFlush` y el stock ya se descontó cuando el costeo FIFO se queda
     * sin lote del que consumir. Si las dos escrituras no compartieran
     * transaccion, el catalogo se quedaria con un precio nuevo procedente de una
     * venta que nunca existio.
     */
    @Test
    void aFailureAfterPricingRollsBackBothTheLineAndTheCatalogPrice() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = unpricedInventory(workshop, product, 10, false);
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(), new AddSaleDetailRequestDTO(
                        product.getId(), null, null, 1, new BigDecimal("250.00"), null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(reload(sale).getDetails()).isEmpty();
        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("0.00");
        assertThat(inventoryRepository.findById(stock.getId())
                .orElseThrow().getStock()).isEqualTo(10);
    }

    /**
     * Con precio de catalogo, un importe escrito a mano es un DESCUENTO de esta
     * venta. Reescribir el catalogo con el seria dejar que una rebaja puntual
     * cambiara el precio del taller.
     */
    @Test
    void aCustomPriceOnAPricedProductDoesNotRewriteTheCatalog() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = inventory(workshop, product, 10);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                product.getId(), null, null, 1, new BigDecimal("80.00"), null));

        assertThat(reload(sale).getDetails())
                .singleElement()
                .satisfies(detail ->
                        assertThat(detail.getPrice()).isEqualByComparingTo("80.00"));
        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("100.00");
    }

    /**
     * El precio de la linea es historia y no se recalcula: una vez asignado el
     * precio del taller, cambiarlo despues no toca la venta que lo estreno.
     */
    @Test
    void theHistoricalLinePriceSurvivesALaterCatalogChange() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.NORMAL);
        Inventory stock = unpricedInventory(workshop, product, 10, true);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                product.getId(), null, null, 1, new BigDecimal("250.00"), null));

        Inventory reloaded = inventoryRepository.findById(stock.getId()).orElseThrow();
        reloaded.setPrice(new BigDecimal("999.00"));
        inventoryRepository.saveAndFlush(reloaded);

        assertThat(reload(sale).getDetails())
                .singleElement()
                .satisfies(detail -> {
                    assertThat(detail.getPrice()).isEqualByComparingTo("250.00");
                    assertThat(detail.getSubTotal()).isEqualByComparingTo("250.00");
                });
    }

    /** Una unidad concreta (SPECIAL) sigue la misma regla que el resto. */
    @Test
    void anUnpricedSpecialItemAlsoTeachesItsPriceToTheCatalog() {
        Workshop workshop = authenticatedWorkshop();
        Product product = product(FamilyCategory.SPECIAL);
        Inventory stock = unpricedInventory(workshop, product, 1, true);
        InventoryItem item = availableItem(stock);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                product.getId(), item.getId(), null, null,
                new BigDecimal("330.00"), null));

        assertThat(reload(sale).getDetails())
                .singleElement()
                .satisfies(detail ->
                        assertThat(detail.getPrice()).isEqualByComparingTo("330.00"));
        assertThat(catalogPriceOf(stock)).isEqualByComparingTo("330.00");
    }

    // ── Precio de un servicio sin precio asignado ────────────────
    //
    // `job.price` tiene el MISMO esquema que el del inventario, sin nulo
    // posible, asi que el cero significa lo mismo: sin asignar. Un taller no
    // regala mano de obra.

    @Test
    void aManualPriceOnAnUnpricedServiceIsStoredInTheSaleAndInTheCatalog() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        Job service_ = jobRepository.save(Job.builder()
                .product(jobProduct)
                .workshop(workshop)
                .price(BigDecimal.ZERO)
                .build());
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, null, 2, new BigDecimal("90.00"), null));

        assertThat(reload(sale).getDetails())
                .singleElement()
                .satisfies(detail -> {
                    assertThat(detail.getPrice()).isEqualByComparingTo("90.00");
                    assertThat(detail.getSubTotal()).isEqualByComparingTo("180.00");
                });
        assertThat(jobRepository.findById(service_.getId())
                .orElseThrow().getPrice()).isEqualByComparingTo("90.00");
    }

    @Test
    void anUnpricedServiceCannotBeSoldWithoutAManualPrice() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        Job service_ = jobRepository.save(Job.builder()
                .product(jobProduct)
                .workshop(workshop)
                .price(BigDecimal.ZERO)
                .build());
        Sale sale = openSale(workshop);

        assertThatThrownBy(() -> service.addDetail(
                sale.getId(), new AddSaleDetailRequestDTO(
                        jobProduct.getId(), null, null, 1, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un precio de venta asignado");

        assertThat(reload(sale).getDetails()).isEmpty();
        assertThat(jobRepository.findById(service_.getId())
                .orElseThrow().getPrice()).isEqualByComparingTo("0.00");
    }

    /** Con precio configurado, una rebaja de mano de obra no toca el catalogo. */
    @Test
    void aCustomPriceOnAPricedServiceDoesNotRewriteTheCatalog() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        Job service_ = job(workshop, jobProduct);
        Sale sale = openSale(workshop);

        service.addDetail(sale.getId(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, null, 1, new BigDecimal("20.00"), null));

        assertThat(reload(sale).getDetails())
                .singleElement()
                .satisfies(detail ->
                        assertThat(detail.getPrice()).isEqualByComparingTo("20.00"));
        assertThat(jobRepository.findById(service_.getId())
                .orElseThrow().getPrice()).isEqualByComparingTo("150.00");
    }

}
