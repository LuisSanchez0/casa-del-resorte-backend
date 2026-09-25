package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;
import com.cns.spring_house.customer.entity.Customer;
import com.cns.spring_house.customer.repository.CustomerRepository;
import com.cns.spring_house.customer.service.CustomerService;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.entity.Inventory;
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
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.ExchangeInvoiceSummaryDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SaleSummaryDTO;
import com.cns.spring_house.sale.dto.SaveForLaterRequestDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.sale.repository.projection.ClosureExchangeInvoiceProjection;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.repository.WorkResultRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * Las tres identidades fiscales de la venta, de punta a punta contra
 * PostgreSQL: el servicio con sus transacciones reales, V40/V41 con su
 * ck_sale_tax_identity, el congelado del cliente y las consultas que leen el
 * nombre de la venta.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleTaxModeDatabaseTest {

    private static final String TEMPORARY_DATABASE = "sale_tax_mode_it";

    private static final String UNRESOLVED_COMPLETION_MESSAGE =
            "Debe seleccionar NIT o Consumidor Final antes de finalizar la venta";

    @Autowired private SaleService service;
    @Autowired private CustomerService customerService;
    @Autowired private SaleRepository saleRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private WorkshopRepository workshopRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private FamilyRepository familyRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private InventoryAcquisitionRepository acquisitionRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobLogRepository jobLogRepository;
    @Autowired private WorkResultRepository workResultRepository;
    @Autowired private JdbcTemplate jdbc;

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

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Datos de partida ─────────────────────────────────────────

    private final Map<UUID, UserAccount> accounts = new HashMap<>();

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Un NIT real: solo digitos, entre 8 y 13, distinto en cada llamada. */
    private String randomNit() {
        return String.valueOf(ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        authenticateAgainst(workshop);
        return workshop;
    }

    private void authenticateAgainst(Workshop workshop) {
        UserAccount account = accounts.computeIfAbsent(
                workshop.getId(), ignored -> persistSeller(workshop));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private UserAccount persistSeller(Workshop workshop) {
        Employee seller = employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("VENDEDOR")
                .lastName("CAJA")
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Role sellerRole = roleRepository.findAll().stream()
                .filter(role -> "SELLER".equals(role.getName()))
                .findFirst()
                .orElseThrow();
        return userAccountRepository.save(UserAccount.builder()
                .employee(seller)
                .role(sellerRole)
                .username("seller_" + unique())
                .password("no-usada-en-pruebas")
                .isActive(true)
                .build());
    }

    private Customer registeredCustomer(String name) {
        return customerRepository.save(Customer.builder()
                .nit(randomNit())
                .name(name)
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

    /** Inventario con su lote de compra: sin lote el FIFO no puede costear. */
    private Inventory inventory(Workshop workshop, Product product, int stock) {
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("100.00"))
                .cost(new BigDecimal("10.00"))
                .stock(stock)
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
                .quantityReceived(stock)
                .quantityRemaining(stock)
                .unitCost(new BigDecimal("10.00"))
                .acquiredAt(LocalDateTime.now())
                .build());
        return inventory;
    }

    private WorkResult pendingJobWorkResult(Workshop workshop, Product jobProduct) {
        Job job = jobRepository.save(Job.builder()
                .product(jobProduct)
                .workshop(workshop)
                .price(new BigDecimal("150.00"))
                .build());
        JobLog jobLog = jobLogRepository.save(JobLog.builder()
                .employee(accounts.get(workshop.getId()).getEmployee())
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        return workResultRepository.save(WorkResult.builder()
                .workshop(workshop)
                .jobLog(jobLog)
                .build());
    }

    /** Agrega dos unidades de un producto NORMAL con existencias. */
    private Inventory addNormalLine(Workshop workshop, UUID saleId) {
        Product normal = product(FamilyCategory.NORMAL);
        Inventory stock = inventory(workshop, normal, 5);
        service.addDetail(saleId, new AddSaleDetailRequestDTO(
                normal.getId(), null, null, 2, null, null));
        return stock;
    }

    private int stockOf(Inventory inventory) {
        return inventoryRepository.findById(inventory.getId()).orElseThrow().getStock();
    }

    private SaleResponseDTO openNit(Customer customer) {
        return service.create(new CreateSaleRequestDTO(
                customer.getId(), null, TaxMode.NIT, null));
    }

    /**
     * Carrito sin NIT, tal como lo abre la caja: sin definir y sin guardar,
     * aunque el vendedor piense emitirlo a Consumidor Final.
     */
    private SaleResponseDTO openDraft(String name) {
        return service.create(new CreateSaleRequestDTO(
                null, null, TaxMode.UNRESOLVED, name));
    }

    private SaleResponseDTO saveForLater(UUID saleId, String referenceName) {
        return service.saveForLater(saleId, new SaveForLaterRequestDTO(referenceName));
    }

    /** La fila tal como quedo en la base, sin pasar por Hibernate. */
    private Map<String, Object> row(UUID saleId) {
        return jdbc.queryForMap("""
                SELECT status, tax_mode, customer_id, customer_name, customer_nit,
                       saved_for_later, completed_at, invoice_type, payment_status
                FROM sale WHERE id = ?
                """, saleId);
    }

    private CompleteSaleRequestDTO invoiceCash(
            TaxMode taxMode, UUID customer, String customerName) {
        return new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH, taxMode, customer, customerName);
    }

    private long customerCount() {
        return customerRepository.count();
    }

    private long saleCountOf(Workshop workshop) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale WHERE workshop_id = ?",
                Long.class, workshop.getId());
    }

    // ── Venta normal con NIT ─────────────────────────────────────

    @Test
    void aNitSaleIsSavedForLaterResumedAndCompletedWithItsCustomerFrozen() {
        Workshop workshop = authenticatedWorkshop();
        Customer juan = registeredCustomer("JUAN PEREZ");

        SaleResponseDTO opened = openNit(juan);
        Inventory stock = addNormalLine(workshop, opened.id());
        assertThat(opened.savedForLater()).isFalse();

        SaleResponseDTO saved = saveForLater(opened.id(), null);
        assertThat(saved.status()).isEqualTo(SaleStatus.PENDING);
        assertThat(saved.savedForLater()).isTrue();

        // Retomarla: mientras esta abierta la identidad es la viva del cliente.
        SaleResponseDTO resumed = service.get(opened.id());
        assertThat(resumed.taxMode()).isEqualTo(TaxMode.NIT);
        assertThat(resumed.customer().nit()).isEqualTo(juan.getNit());
        assertThat(resumed.customerName()).isNull();
        assertThat(resumed.customerNit()).isNull();

        SaleResponseDTO completed = service.completeSale(opened.id(),
                new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH));

        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.taxMode()).isEqualTo(TaxMode.NIT);
        assertThat(completed.customerName()).isEqualTo("JUAN PEREZ");
        assertThat(completed.customerNit()).isEqualTo(juan.getNit());
        assertThat(completed.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(stockOf(stock)).isEqualTo(3);
    }

    // ── Carrito sin NIT: nunca CF antes de emitir ────────────────

    /** A/B/I. Un carrito que va a ser CF se persiste SIN DEFINIR y sin guardar. */
    @Test
    void anAnonymousCartMeantForConsumidorFinalIsPersistedUnresolvedAndUnsaved() {
        Workshop workshop = authenticatedWorkshop();

        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());

        Map<String, Object> persisted = row(opened.id());
        assertThat(persisted.get("tax_mode")).isEqualTo("UNRESOLVED");
        assertThat(persisted.get("status")).isEqualTo("PENDING");
        assertThat(persisted.get("saved_for_later")).isEqualTo(false);
        assertThat(persisted.get("customer_name")).isNull();

        // Pedirlo como CF desde el alta no es posible.
        assertThatThrownBy(() -> service.create(
                new CreateSaleRequestDTO(null, null, TaxMode.CF, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Consumidor Final se elige al emitir");
        // Y la tabla tampoco admite forzarlo mientras siga abierto.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE sale SET tax_mode = 'CF' WHERE id = ?", opened.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_sale_tax_identity");
    }

    /** C. UNRESOLVED -> CF y PENDING -> COMPLETED ocurren en la misma transaccion. */
    @Test
    void completingAsConsumidorFinalMovesToCfAndCompletedAtOnce() {
        Workshop workshop = authenticatedWorkshop();
        long customersBefore = customerCount();

        SaleResponseDTO opened = openDraft(null);
        Inventory stock = addNormalLine(workshop, opened.id());
        SaleResponseDTO completed = service.completeSale(opened.id(),
                invoiceCash(TaxMode.CF, null, "María López"));

        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.taxMode()).isEqualTo(TaxMode.CF);
        assertThat(completed.customer()).isNull();
        assertThat(completed.customerName()).isEqualTo("MARÍA LÓPEZ");
        assertThat(completed.customerNit()).isNull();

        Map<String, Object> persisted = row(opened.id());
        assertThat(persisted.get("tax_mode")).isEqualTo("CF");
        assertThat(persisted.get("status")).isEqualTo("COMPLETED");
        assertThat(persisted.get("customer_nit")).isNull();
        // Ni un Customer inventado, ni un "CF" en la columna unica de NIT.
        assertThat(customerCount()).isEqualTo(customersBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer WHERE nit = 'CF'", Long.class)).isZero();
        assertThat(stockOf(stock)).isEqualTo(3);
    }

    /** D. Si emitir falla, el paso a CF se deshace con todo lo demas. */
    @Test
    void aFailedCompletionAsConsumidorFinalLeavesNoCfBehind() {
        Workshop workshop = authenticatedWorkshop();
        SaleResponseDTO opened = openDraft("Carlos Gómez");
        Inventory stock = addNormalLine(workshop, opened.id());

        // Factura normal sin forma de pago: el rechazo llega DESPUES de haber
        // aplicado CF y COMPLETED sobre la entidad.
        assertThatThrownBy(() -> service.completeSale(opened.id(),
                new CompleteSaleRequestDTO(InvoiceType.INVOICE, null, TaxMode.CF, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El metodo de pago es obligatorio para una factura normal");

        Map<String, Object> persisted = row(opened.id());
        assertThat(persisted.get("tax_mode")).isEqualTo("UNRESOLVED");
        assertThat(persisted.get("status")).isEqualTo("PENDING");
        assertThat(persisted.get("customer_name")).isEqualTo("CARLOS GÓMEZ");
        assertThat(persisted.get("completed_at")).isNull();
        assertThat(persisted.get("invoice_type")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale WHERE tax_mode = 'CF' AND status = 'PENDING'",
                Long.class)).isZero();
        // Lo reservado sigue reservado, ni vendido ni devuelto.
        assertThat(stockOf(stock)).isEqualTo(3);
    }

    @Test
    void aNamelessConsumidorFinalSaleCompletes() {
        Workshop workshop = authenticatedWorkshop();

        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());
        SaleResponseDTO completed = service.completeSale(opened.id(),
                invoiceCash(TaxMode.CF, null, null));

        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.taxMode()).isEqualTo(TaxMode.CF);
        assertThat(completed.customer()).isNull();
        // Sin nombre: se presenta como "Consumidor Final".
        assertThat(completed.customerName()).isNull();
        assertThat(completed.customerNit()).isNull();
    }

    // ── Guardar para despues ─────────────────────────────────────

    /** E. Sin nombre de referencia no se guarda: seguiria siendo irreconocible. */
    @Test
    void anUnnamedCartCannotBeSavedForLater() {
        Workshop workshop = authenticatedWorkshop();
        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());

        assertThatThrownBy(() -> saveForLater(opened.id(), "   "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El nombre es obligatorio para guardar para despues una venta sin NIT");

        assertThat(row(opened.id()).get("saved_for_later")).isEqualTo(false);
        // Y la tabla tampoco admite una guardada sin nombre.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE sale SET saved_for_later = TRUE WHERE id = ?", opened.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** F/H. Con nombre queda SIN DEFINIR, retomable y reconocible. */
    @Test
    void aSavedUnresolvedSaleKeepsItsNameAndReopensWithIt() {
        Workshop workshop = authenticatedWorkshop();
        long customersBefore = customerCount();

        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());
        SaleResponseDTO saved = saveForLater(opened.id(), "Carlos Gómez");
        assertThat(saved.savedForLater()).isTrue();
        assertThat(saved.taxMode()).isEqualTo(TaxMode.UNRESOLVED);

        SaleResponseDTO reopened = service.get(opened.id());
        assertThat(reopened.status()).isEqualTo(SaleStatus.PENDING);
        assertThat(reopened.taxMode()).isEqualTo(TaxMode.UNRESOLVED);
        assertThat(reopened.customer()).isNull();
        assertThat(reopened.customerName()).isEqualTo("CARLOS GÓMEZ");
        assertThat(reopened.customerNit()).isNull();
        assertThat(reopened.details()).hasSize(1);
        assertThat(customerCount()).isEqualTo(customersBefore);

        // Se encuentra por su nombre, tecleado como sea.
        List<UUID> found = service.getAll(new SearchSaleRequestDTO(
                "carlos", null, null, null, null, null, null, null, null,
                SaleStatus.PENDING, null, null)).stream()
                .map(SaleResponseDTO::id)
                .toList();
        assertThat(found).containsExactly(opened.id());
    }

    /** G. Las guardadas y los carritos sin guardar se consultan por separado. */
    @Test
    void thePendingQuerySeparatesSavedSalesFromUnsavedCarts() {
        Workshop workshop = authenticatedWorkshop();
        Customer juan = registeredCustomer("JUAN PEREZ");
        SaleResponseDTO savedNit = openNit(juan);
        saveForLater(savedNit.id(), null);
        SaleResponseDTO savedUnresolved = openDraft(null);
        saveForLater(savedUnresolved.id(), "Carlos Gómez");
        SaleResponseDTO draft = openDraft(null);
        UUID employee = accounts.get(workshop.getId()).getEmployee().getId();

        assertThat(pending(employee, true))
                .containsExactlyInAnyOrder(savedNit.id(), savedUnresolved.id());
        assertThat(pending(employee, false)).containsExactly(draft.id());
        assertThat(pending(employee, null))
                .containsExactlyInAnyOrder(savedNit.id(), savedUnresolved.id(), draft.id());
    }

    private List<UUID> pending(UUID employee, Boolean savedForLater) {
        return service.getAll(new SearchSaleRequestDTO(
                null, null, null, null, null, null, null, null, employee,
                SaleStatus.PENDING, null, null, savedForLater)).stream()
                .map(SaleResponseDTO::id)
                .toList();
    }

    @Test
    void anUnresolvedSaleCannotCompleteAndStaysExactlyAsItWas() {
        Workshop workshop = authenticatedWorkshop();
        SaleResponseDTO opened = openDraft(null);
        Inventory stock = addNormalLine(workshop, opened.id());
        saveForLater(opened.id(), "Carlos Gómez");

        // Una caja vieja que solo envia el documento.
        assertThatThrownBy(() -> service.completeSale(opened.id(),
                new CompleteSaleRequestDTO(InvoiceType.INVOICE, PaymentMethod.CASH)))
                .isInstanceOf(ValidationException.class)
                .hasMessage(UNRESOLVED_COMPLETION_MESSAGE);

        SaleResponseDTO after = service.get(opened.id());
        assertThat(after.status()).isEqualTo(SaleStatus.PENDING);
        assertThat(after.taxMode()).isEqualTo(TaxMode.UNRESOLVED);
        assertThat(after.savedForLater()).isTrue();
        assertThat(after.invoiceType()).isNull();
        assertThat(after.completedAt()).isNull();
        assertThat(stockOf(stock)).isEqualTo(3);
    }

    // ── Finalizar una pendiente ──────────────────────────────────

    /** G (historial). Pendiente -> NIT congela al cliente elegido. */
    @Test
    void aSavedUnresolvedSaleCompletesWithNitAndFreezesThatCustomer() {
        Workshop workshop = authenticatedWorkshop();
        Customer carlos = registeredCustomer("CARLOS GOMEZ LOPEZ");
        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());
        saveForLater(opened.id(), "Carlos Gómez");

        assertThatThrownBy(() -> service.completeSale(opened.id(),
                invoiceCash(TaxMode.NIT, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El cliente es obligatorio");

        SaleResponseDTO completed = service.completeSale(opened.id(),
                invoiceCash(TaxMode.NIT, carlos.getId(), null));

        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.taxMode()).isEqualTo(TaxMode.NIT);
        assertThat(completed.customer().id()).isEqualTo(carlos.getId());
        assertThat(completed.customerName()).isEqualTo("CARLOS GOMEZ LOPEZ");
        assertThat(completed.customerNit()).isEqualTo(carlos.getNit());
        // Facturarle no toca el maestro.
        Customer reloaded = customerRepository.findById(carlos.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("CARLOS GOMEZ LOPEZ");
    }

    /** H (historial). Pendiente -> CF congela el nombre y ningun NIT. */
    @Test
    void aSavedUnresolvedSaleCompletesAsConsumidorFinal() {
        Workshop workshop = authenticatedWorkshop();
        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());
        saveForLater(opened.id(), "Carlos Gómez");

        SaleResponseDTO completed = service.completeSale(opened.id(),
                invoiceCash(TaxMode.CF, null, "Carlos Gómez"));

        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.taxMode()).isEqualTo(TaxMode.CF);
        assertThat(completed.customer()).isNull();
        assertThat(completed.customerName()).isEqualTo("CARLOS GÓMEZ");
        assertThat(completed.customerNit()).isNull();
    }

    // ── El historial no se reescribe ─────────────────────────────

    /**
     * Juan Perez / 12345678 compra; despues se corrige el cliente. La venta,
     * y todo lo que la lee, sigue diciendo lo que se facturo.
     */
    @Test
    void editingTheCustomerAfterwardsNeverRewritesTheSale() {
        Workshop workshop = authenticatedWorkshop();
        Customer juan = customerRepository.findById(customerService.create(
                new CreateCustomerRequestDTO(randomNit(), "Juan Pérez")).id()).orElseThrow();
        String originalNit = juan.getNit();

        SaleResponseDTO opened = openNit(juan);
        addNormalLine(workshop, opened.id());
        service.completeSale(opened.id(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null));
        SaleResponseDTO before = service.get(opened.id());
        assertThat(before.customerName()).isEqualTo("JUAN PÉREZ");
        assertThat(before.customerNit()).isEqualTo(originalNit);

        String newNit = randomNit();
        customerService.update(juan.getId(),
                new UpdateCustomerRequestDTO(newNit, "Juan Pérez Corregido"));

        SaleResponseDTO after = service.get(opened.id());
        assertThat(after.customerName()).isEqualTo("JUAN PÉREZ");
        assertThat(after.customerNit()).isEqualTo(originalNit);
        // La relacion sigue viva y muestra el cliente de hoy; el historial no.
        assertThat(after.customer().nit()).isEqualTo(newNit);

        ExchangeInvoiceSummaryDTO portfolio = service.getExchangeInvoices(null, 0, 20)
                .getContent().stream()
                .filter(summary -> summary.id().equals(opened.id()))
                .findFirst()
                .orElseThrow();
        assertThat(portfolio.customerName()).isEqualTo("JUAN PÉREZ");
        assertThat(portfolio.customerNit()).isEqualTo(originalNit);

        List<ClosureExchangeInvoiceProjection> issued =
                saleRepository.findExchangeInvoicesIssuedBetween(
                        workshop.getId(),
                        LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(1));
        assertThat(issued).extracting(ClosureExchangeInvoiceProjection::getCustomerName)
                .containsExactly("JUAN PÉREZ");

        // Y se sigue encontrando por lo que se facturo.
        assertThat(service.searchCompletedSales(originalNit, null))
                .extracting(SaleSummaryDTO::id).containsExactly(opened.id());
    }

    @Test
    void aCancelledNitSaleKeepsTheCustomerItHadWhenCancelled() {
        Workshop workshop = authenticatedWorkshop();
        Customer juan = registeredCustomer("JUAN PEREZ");
        String originalNit = juan.getNit();
        SaleResponseDTO opened = openNit(juan);
        addNormalLine(workshop, opened.id());

        service.cancel(opened.id(), new CancelSaleRequestDTO("Cliente desistio"));
        customerService.update(juan.getId(),
                new UpdateCustomerRequestDTO(randomNit(), "OTRO NOMBRE"));

        SaleResponseDTO cancelled = service.get(opened.id());
        assertThat(cancelled.status()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(cancelled.customerName()).isEqualTo("JUAN PEREZ");
        assertThat(cancelled.customerNit()).isEqualTo(originalNit);
    }

    // ── Un NIT vacio nunca es CF ─────────────────────────────────

    @Test
    void aSaleWithoutCustomerAndWithoutModeIsRejectedNotTurnedIntoConsumidorFinal() {
        Workshop workshop = authenticatedWorkshop();

        assertThatThrownBy(() -> service.create(new CreateSaleRequestDTO(null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El cliente es obligatorio");
        assertThatThrownBy(() -> service.create(new CreateSaleRequestDTO(
                null, null, null, "María López")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El cliente es obligatorio");

        assertThat(saleCountOf(workshop)).isZero();
    }

    // ── Clientes ─────────────────────────────────────────────────

    @Test
    void aRealNitIsStillUnique() {
        authenticatedWorkshop();
        String nit = randomNit();
        customerService.create(new CreateCustomerRequestDTO(nit, "PRIMERO"));

        assertThatThrownBy(() -> customerService.create(
                new CreateCustomerRequestDTO(nit, "SEGUNDO")))
                .isInstanceOf(EntityAlreadyExistsException.class);
    }

    /** El nombre de referencia es una etiqueta, no una identidad: no se funde. */
    @Test
    void twoSavedSalesWithTheSameReferenceNameStaySeparate() {
        Workshop workshop = authenticatedWorkshop();
        long customersBefore = customerCount();

        SaleResponseDTO first = openDraft(null);
        SaleResponseDTO second = openDraft(null);
        saveForLater(first.id(), "Juan Pérez");
        saveForLater(second.id(), "juan pérez");

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(service.get(first.id()).customerName())
                .isEqualTo(service.get(second.id()).customerName());
        assertThat(customerCount()).isEqualTo(customersBefore);
        assertThat(saleCountOf(workshop)).isEqualTo(2);
    }

    // ── Productos, trabajos, anulacion y reversion ───────────────

    @Test
    void aConsumidorFinalSaleConsumesStockAndSellsWorkExactlyLikeANitSale() {
        Workshop workshop = authenticatedWorkshop();
        Product jobProduct = product(FamilyCategory.JOB);
        WorkResult workResult = pendingJobWorkResult(workshop, jobProduct);

        SaleResponseDTO opened = openDraft(null);
        Inventory stock = addNormalLine(workshop, opened.id());
        service.addDetail(opened.id(), new AddSaleDetailRequestDTO(
                jobProduct.getId(), null, workResult.getId(), 1, null, null));
        service.completeSale(opened.id(), invoiceCash(TaxMode.CF, null, null));

        assertThat(stockOf(stock)).isEqualTo(3);
        assertThat(workResultRepository.findById(workResult.getId()).orElseThrow()
                .getStatus()).isEqualTo(WorkResultStatus.SOLD);

        // Y se revierte como cualquier otra, conservando su identidad fiscal.
        SaleResponseDTO reversed = service.reverse(opened.id(),
                new ReverseSaleRequestDTO("Devolucion de prueba", null));
        assertThat(reversed.status()).isEqualTo(SaleStatus.REVERSED);
        assertThat(reversed.taxMode()).isEqualTo(TaxMode.CF);
        assertThat(stockOf(stock)).isEqualTo(5);
    }

    @Test
    void cartsAndSavedSalesAreCancelledOrDiscardedLikeAnyOpenSale() {
        Workshop workshop = authenticatedWorkshop();

        SaleResponseDTO toCancel = openDraft(null);
        Inventory stock = addNormalLine(workshop, toCancel.id());
        saveForLater(toCancel.id(), "Carlos Gómez");
        SaleResponseDTO cancelled = service.cancel(toCancel.id(),
                new CancelSaleRequestDTO("Cliente no regreso"));
        assertThat(cancelled.status()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(cancelled.taxMode()).isEqualTo(TaxMode.UNRESOLVED);
        assertThat(cancelled.customerName()).isEqualTo("CARLOS GÓMEZ");
        assertThat(stockOf(stock)).isEqualTo(5);

        // Un carrito abandonado sin nombre se descarta devolviendo lo reservado.
        SaleResponseDTO abandoned = openDraft(null);
        Inventory other = addNormalLine(workshop, abandoned.id());
        assertThat(stockOf(other)).isEqualTo(3);
        service.delete(abandoned.id());
        assertThat(saleRepository.findById(abandoned.id())).isEmpty();
        assertThat(stockOf(other)).isEqualTo(5);
    }

    // ── Cartera, cierre y buscador de facturas ───────────────────

    @Test
    void aConsumidorFinalExchangeInvoiceShowsItsOwnNameWhereverTheSaleIsRead() {
        Workshop workshop = authenticatedWorkshop();
        SaleResponseDTO opened = openDraft(null);
        addNormalLine(workshop, opened.id());
        service.completeSale(opened.id(), new CompleteSaleRequestDTO(
                InvoiceType.EXCHANGE_INVOICE, null, TaxMode.CF, null, "María López"));

        ExchangeInvoiceSummaryDTO row = service.getExchangeInvoices(null, 0, 20)
                .getContent().stream()
                .filter(summary -> summary.id().equals(opened.id()))
                .findFirst()
                .orElseThrow();
        assertThat(row.taxMode()).isEqualTo(TaxMode.CF);
        assertThat(row.customer()).isNull();
        assertThat(row.customerName()).isEqualTo("MARÍA LÓPEZ");
        assertThat(row.customerNit()).isNull();

        // Fragmento ASCII a proposito: LOWER() de una letra acentuada depende del
        // locale del servidor, y eso no es lo que se prueba aqui.
        List<SaleSummaryDTO> suggestions = service.searchCompletedSales("pez", null);
        assertThat(suggestions).extracting(SaleSummaryDTO::id).containsExactly(opened.id());
        assertThat(suggestions.get(0).taxMode()).isEqualTo(TaxMode.CF);

        List<ClosureExchangeInvoiceProjection> issued =
                saleRepository.findExchangeInvoicesIssuedBetween(
                        workshop.getId(),
                        LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(1));
        assertThat(issued).extracting(ClosureExchangeInvoiceProjection::getCustomerName)
                .containsExactly("MARÍA LÓPEZ");
    }

    // ── Aislamiento por taller ───────────────────────────────────

    @Test
    void anotherWorkshopCannotSeeSaveOrCompleteAnotherWorkshopsSale() {
        Workshop owner = authenticatedWorkshop();
        SaleResponseDTO opened = openDraft(null);
        addNormalLine(owner, opened.id());
        saveForLater(opened.id(), "Carlos Gómez");

        authenticatedWorkshop();

        assertThatThrownBy(() -> service.get(opened.id()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> saveForLater(opened.id(), "Otro"))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.completeSale(opened.id(),
                invoiceCash(TaxMode.CF, null, null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(service.getAll(new SearchSaleRequestDTO(
                "carlos", null, null, null, null, null, null, null, null,
                null, null, null))).isEmpty();

        authenticateAgainst(owner);
        SaleResponseDTO untouched = service.get(opened.id());
        assertThat(untouched.taxMode()).isEqualTo(TaxMode.UNRESOLVED);
        assertThat(untouched.customerName()).isEqualTo("CARLOS GÓMEZ");
    }

    // ── Lo que sostiene la propia base ───────────────────────────

    /** Aunque alguien se salte el servicio, la tabla no admite ninguna identidad ambigua. */
    @Test
    void theTableItselfRejectsEveryAmbiguousIdentity() {
        Workshop workshop = authenticatedWorkshop();
        Customer juan = registeredCustomer("JUAN PEREZ");

        SaleResponseDTO draft = openDraft(null);
        SaleResponseDTO saved = openDraft(null);
        saveForLater(saved.id(), "Carlos Gómez");
        SaleResponseDTO nitOpen = openNit(juan);
        SaleResponseDTO cf = openDraft(null);
        addNormalLine(workshop, cf.id());
        service.completeSale(cf.id(), invoiceCash(TaxMode.CF, null, null));
        SaleResponseDTO nitDone = openNit(juan);
        addNormalLine(workshop, nitDone.id());
        service.completeSale(nitDone.id(), invoiceCash(null, null, null));

        // Sin definir y emitida: nunca.
        assertRejected("UPDATE sale SET status = 'COMPLETED', completed_at = now() WHERE id = ?", draft.id());
        // Guardada sin nombre de referencia.
        assertRejected("UPDATE sale SET customer_name = NULL WHERE id = ?", saved.id());
        // CF abierto, CF con cliente, CF con un NIT colado.
        assertRejected("UPDATE sale SET status = 'PENDING' WHERE id = ?", cf.id());
        assertRejected("UPDATE sale SET customer_id = ? WHERE id = ?", juan.getId(), cf.id());
        assertRejected("UPDATE sale SET customer_nit = '12345678' WHERE id = ?", cf.id());
        // NIT abierto con un nombre suelto, o sin cliente.
        assertRejected("UPDATE sale SET customer_name = 'OTRO' WHERE id = ?", nitOpen.id());
        assertRejected("UPDATE sale SET customer_id = NULL WHERE id = ?", nitOpen.id());
        // NIT cerrado sin su identidad congelada.
        assertRejected("UPDATE sale SET customer_nit = NULL WHERE id = ?", nitDone.id());
        assertRejected("UPDATE sale SET customer_name = NULL WHERE id = ?", nitDone.id());
        // Un nombre en blanco no es un nombre.
        assertRejected("UPDATE sale SET customer_name = '   ' WHERE id = ?", saved.id());

        assertThat(saleCountOf(workshop)).isEqualTo(5);
    }

    private void assertRejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .as(sql)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

}
