package com.cns.spring_house.dailylog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogProductRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.dto.DailyLogServiceRequestDTO;
import com.cns.spring_house.dailylog.dto.SearchDailyLogRequestDTO;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El Daily Log contra PostgreSQL de verdad, que es donde se puede demostrar lo
 * unico que no se ve en una prueba con mocks: que lo guardado es TEXTO y no una
 * relacion viva con el catalogo.
 *
 * Aqui se comprueba que el esquema no tiene `product_id` ni `job_id`, que
 * renombrar la pieza no toca el historico y que borrar el servicio del catalogo
 * no se lleva el registro por delante.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class DailyLogServiceDatabaseTest {

    private static final String TEMPORARY_DATABASE = "daily_log_service_it";

    private static String jdbcUrl;
    private static String jdbcUsername;
    private static String jdbcPassword;

    @Autowired
    private DailyLogService service;

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
    private JobRepository jobRepository;

    @Autowired
    private SaleRepository saleRepository;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        // Se guardan en campos porque dos pruebas abren su propia conexion JDBC
        // directa, ademas del datasource de Spring.
        jdbcUrl = TestDatabases.provision(TEMPORARY_DATABASE);
        jdbcUsername = TestDatabases.username();
        jdbcPassword = TestDatabases.password();

        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> jdbcUsername);
        registry.add("spring.datasource.password", () -> jdbcPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Spring cachea un contexto por cada prueba con base propia y cada uno
        // se queda con su pool durante toda la ejecucion. Con el tamaño por
        // defecto, sumar esta clase agotaba las conexiones de PostgreSQL y la
        // suite moria con "sorry, too many clients already" en la prueba que
        // tocara arrancar despues. Mismo tope que SaleDashboardQueriesTest.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
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
     * Taller con una secretaria autenticada. El rol SECRETARY tiene que existir
     * ya sembrado por V25: si la migracion no se aplicara, esta prueba no
     * podria ni empezar.
     */
    private Workshop authenticatedWorkshop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        Role secretary = roleRepository.findAll().stream()
                .filter(role -> "SECRETARY".equals(role.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El rol SECRETARY no esta sembrado en la base de prueba"));
        UserAccount account = userAccountRepository.save(UserAccount.builder()
                .employee(employee(workshop))
                .role(secretary)
                .username("secretaria_" + unique())
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

    private Product product(FamilyCategory category, String name) {
        String code = unique();
        Family family = familyRepository.save(Family.builder()
                .code(code)
                .name(category.name() + " " + code)
                .requiresModel(false)
                .category(category)
                .build());
        return productRepository.save(Product.builder()
                .family(family)
                .name(name)
                .attributes(new HashMap<>())
                .build());
    }

    private Job job(Workshop workshop, String name) {
        return jobRepository.save(Job.builder()
                .product(product(FamilyCategory.JOB, name))
                .workshop(workshop)
                .price(new BigDecimal("150.00"))
                .build());
    }

    /**
     * Venta ya emitida. Se guarda directamente porque el Daily Log solo la usa
     * como referencia: no mira sus detalles ni su inventario.
     */
    private Sale completedSale(Workshop workshop) {
        return saleRepository.save(Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.INVOICE)
                .paymentStatus(PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .total(new BigDecimal("900.00"))
                .build());
    }

    private Sale saleWith(Workshop workshop, SaleStatus status) {
        return saleRepository.save(Sale.builder()
                .taxMode(status == SaleStatus.PENDING ? TaxMode.UNRESOLVED : TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(status)
                .total(BigDecimal.ZERO)
                .build());
    }

    private DailyLogProductRequestDTO productLine(
            String name, String unitPrice, int quantity, String subtotal) {
        return new DailyLogProductRequestDTO(
                name, new BigDecimal(unitPrice), quantity, new BigDecimal(subtotal));
    }

    private DailyLogServiceRequestDTO serviceLine(
            String name, String unitPrice, int quantity, String subtotal) {
        return new DailyLogServiceRequestDTO(
                name, new BigDecimal(unitPrice), quantity, new BigDecimal(subtotal));
    }

    private List<String> columnsOf(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, jdbcUsername, jdbcPassword);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_name = '""" + table + "'")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("column_name"));
            }
        }
        return columns;
    }

    /**
     * Las pruebas comparten la base temporal, asi que todo recuento se acota a
     * la venta de la prueba: un COUNT global contaria los registros que dejaron
     * las demas.
     */
    private long logsOf(Sale sale) throws SQLException {
        return countRows("SELECT COUNT(*) FROM daily_log WHERE sale_id = '"
                + sale.getId() + "'");
    }

    private long linesOf(Sale sale, String table) throws SQLException {
        return countRows("SELECT COUNT(*) FROM " + table + " line"
                + " JOIN daily_log log ON log.id = line.daily_log_id"
                + " WHERE log.sale_id = '" + sale.getId() + "'");
    }

    private long countRows(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl, jdbcUsername, jdbcPassword);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    // ── El esquema no guarda relaciones ──────────────────────────

    /**
     * La comprobacion mas literal del requisito: las columnas que existen y,
     * sobre todo, las que NO existen.
     */
    @Test
    void theLineTablesHaveNoForeignKeyToTheCatalogue() throws SQLException {
        assertThat(columnsOf("daily_log_product"))
                .containsExactlyInAnyOrder(
                        "id", "daily_log_id", "name", "unit_price", "quantity", "subtotal")
                .doesNotContain("product_id");

        assertThat(columnsOf("daily_log_service"))
                .containsExactlyInAnyOrder(
                        "id", "daily_log_id", "name", "unit_price", "quantity", "subtotal")
                .doesNotContain("job_id");
    }

    // ── Alta completa ────────────────────────────────────────────

    @Test
    void aLogIsStoredWithItsSaleAndItsLinesAsPlainText() throws SQLException {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "HOJA DE RESORTE FREIGHTLINER M2");
        job(workshop, "ALINEACION");
        Sale sale = completedSale(workshop);

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("HOJA DE RESORTE FREIGHTLINER M2", "450.00", 2, "900.00")),
                List.of(serviceLine("ALINEACION", "150.00", 1, "150.00")),
                "Cliente pidio revisar nuevamente la suspension."));

        assertThat(created.saleId()).isEqualTo(sale.getId());
        assertThat(created.products()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("HOJA DE RESORTE FREIGHTLINER M2");
            assertThat(line.unitPrice()).isEqualByComparingTo("450.00");
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.subtotal()).isEqualByComparingTo("900.00");
        });
        assertThat(created.services()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("ALINEACION");
            assertThat(line.subtotal()).isEqualByComparingTo("150.00");
        });
        assertThat(created.workshop().id()).isEqualTo(workshop.getId());
        assertThat(created.employee()).isNotNull();

        assertThat(countRows(
                "SELECT COUNT(*) FROM daily_log_product WHERE daily_log_id = '"
                + created.id() + "'")).isEqualTo(1);
        assertThat(countRows(
                "SELECT COUNT(*) FROM daily_log_service WHERE daily_log_id = '"
                + created.id() + "'")).isEqualTo(1);
    }

    /** El nombre entra con otra grafia y se guarda el canonico del catalogo. */
    @Test
    void theStoredNameIsAlwaysTheCatalogueOne() {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "BUJE DE SUSPENSION");
        job(workshop, "BALANCEO GENERAL");
        Sale sale = completedSale(workshop);

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("  buje de suspension ", "75.00", 4, "300.00")),
                List.of(serviceLine(" balanceo general  ", "100.00", 1, "100.00")),
                null));

        assertThat(created.products().get(0).name()).isEqualTo("BUJE DE SUSPENSION");
        assertThat(created.services().get(0).name()).isEqualTo("BALANCEO GENERAL");
    }

    // ── El historico no cambia si cambia el catalogo ─────────────

    /**
     * La prueba que demuestra que no queda relacion viva: se renombra la pieza
     * en el catalogo y el registro sigue diciendo lo que decia.
     */
    @Test
    void renamingTheProductDoesNotRewriteTheLog() {
        Workshop workshop = authenticatedWorkshop();
        Product catalogue = product(FamilyCategory.NORMAL, "HOJA ORIGINAL");
        Sale sale = completedSale(workshop);

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("HOJA ORIGINAL", "450.00", 2, "900.00")),
                List.of(),
                null));

        catalogue.setName("HOJA RENOMBRADA EN CATALOGO");
        productRepository.save(catalogue);

        assertThat(service.get(created.id()).products().get(0).name())
                .isEqualTo("HOJA ORIGINAL");
    }

    @Test
    void renamingTheServiceDoesNotRewriteTheLog() {
        Workshop workshop = authenticatedWorkshop();
        Job catalogue = job(workshop, "ALINEACION ORIGINAL");
        Sale sale = completedSale(workshop);

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(),
                List.of(serviceLine("ALINEACION ORIGINAL", "150.00", 1, "150.00")),
                null));

        Product jobProduct = catalogue.getProduct();
        jobProduct.setName("ALINEACION RENOMBRADA");
        productRepository.save(jobProduct);

        assertThat(service.get(created.id()).services().get(0).name())
                .isEqualTo("ALINEACION ORIGINAL");
    }

    /**
     * Y si el servicio desaparece del catalogo, el registro sobrevive: no hay FK
     * que lo ate, asi que borrar el Job no puede arrastrarlo ni bloquearse.
     */
    @Test
    void deletingTheServiceFromTheCatalogueLeavesTheLogIntact() {
        Workshop workshop = authenticatedWorkshop();
        Job catalogue = job(workshop, "SERVICIO QUE SE RETIRA");
        Sale sale = completedSale(workshop);

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(),
                List.of(serviceLine("SERVICIO QUE SE RETIRA", "150.00", 1, "150.00")),
                null));

        jobRepository.delete(catalogue);

        assertThat(jobRepository.findById(catalogue.getId())).isEmpty();
        assertThat(service.get(created.id()).services().get(0).name())
                .isEqualTo("SERVICIO QUE SE RETIRA");
    }

    // ── Nombres inventados ───────────────────────────────────────

    /**
     * La linea buena tampoco se guarda: el POST entero se deshace, y la venta
     * que iba a quedar registrada sigue sin registro alguno.
     */
    @Test
    void anInventedProductNameLeavesNothingBehind() throws SQLException {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "PIEZA REAL");
        Sale sale = completedSale(workshop);

        assertThatThrownBy(() -> service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(
                        productLine("PIEZA REAL", "75.00", 4, "300.00"),
                        productLine("PRODUCTO INVENTADO 123", "10.00", 1, "10.00")),
                List.of(),
                null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No existe un producto con el nombre indicado");

        assertThat(logsOf(sale)).isZero();
        assertThat(linesOf(sale, "daily_log_product")).isZero();
    }

    @Test
    void anInventedServiceNameLeavesNothingBehind() throws SQLException {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "OTRA PIEZA REAL");
        Sale sale = completedSale(workshop);

        assertThatThrownBy(() -> service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("OTRA PIEZA REAL", "75.00", 4, "300.00")),
                List.of(serviceLine("SERVICIO INVENTADO 123", "50.00", 1, "50.00")),
                null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No existe un servicio con el nombre indicado");

        assertThat(logsOf(sale)).isZero();
        // Ni siquiera la linea de producto, que si era valida.
        assertThat(linesOf(sale, "daily_log_product")).isZero();
    }

    // ── Transformacion vendida ───────────────────────────────────

    /**
     * El producto de SALIDA de una transformacion es un producto de catalogo
     * como cualquier otro, asi que la bitacora ya lo acepta como linea de
     * PRODUCTO sin tocar la validacion. Es la razon por la que este arreglo no
     * necesita una tercera categoria de linea.
     *
     * Se registra con el precio al que se vendio, que en una transformacion es
     * el UNICO precio real: el inventario de salida nace en cero porque nadie le
     * ha puesto precio todavia.
     */
    @Test
    void theOutputProductOfATransformationIsLoggedAsAnOrdinaryProductLine() {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.SPECIAL, "MUELLE RECTIFICADO 60X10");
        Sale sale = completedSale(workshop);

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("MUELLE RECTIFICADO 60X10", "380.00", 1, "380.00")),
                List.of(),
                null));

        assertThat(created.products()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("MUELLE RECTIFICADO 60X10");
            assertThat(line.unitPrice()).isEqualByComparingTo("380.00");
        });
        assertThat(created.services()).isEmpty();
    }

    /**
     * Y el texto FISCAL con el que se imprimio esa transformacion NO entra.
     *
     * `SaleDetail.invoiceDescription` es texto libre que escribe el cajero, y
     * darlo por bueno seria aceptar cualquier cadena con tal de que las
     * transformaciones se pudieran registrar. Lo que hace registrable la linea
     * es el nombre canonico del producto de salida, que el catalogo si conoce.
     */
    @Test
    void theFiscalTextOfASoldTransformationIsStillRejected() throws SQLException {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.SPECIAL, "MUELLE RECTIFICADO 70X12");
        Sale sale = completedSale(workshop);

        assertThatThrownBy(() -> service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("AMORTIGUADOR RECTIFICADO A MEDIDA", "380.00", 1, "380.00")),
                List.of(),
                null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No existe un producto con el nombre indicado");

        assertThat(logsOf(sale)).isZero();
        assertThat(linesOf(sale, "daily_log_product")).isZero();
    }

    /**
     * Un servicio de OTRO taller no vale como servicio de este: el catalogo de
     * servicios es por taller, y es tambien lo unico que el autocompletado
     * ofrece.
     */
    @Test
    void aServiceFromAnotherWorkshopDoesNotCount() {
        Workshop other = authenticatedWorkshop();
        job(other, "SERVICIO DE OTRO TALLER");

        Workshop mine = authenticatedWorkshop();
        Sale sale = completedSale(mine);

        assertThatThrownBy(() -> service.create(new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(),
                List.of(serviceLine("SERVICIO DE OTRO TALLER", "150.00", 1, "150.00")),
                null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No existe un servicio con el nombre indicado");
    }

    // ── Venta de referencia ──────────────────────────────────────

    @Test
    void aSaleThatIsNotCompletedIsRejected() {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "PIEZA");
        Sale pending = saleWith(workshop, SaleStatus.PENDING);

        assertThatThrownBy(() -> service.create(new CreateDailyLogRequestDTO(
                pending.getId(),
                List.of(productLine("PIEZA", "75.00", 4, "300.00")),
                List.of(),
                null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anIssuedExchangeInvoiceAwaitingPaymentIsAccepted() {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "PIEZA CAMBIARIA");
        Sale exchangeInvoice = saleRepository.save(Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .invoiceType(InvoiceType.EXCHANGE_INVOICE)
                .paymentStatus(PaymentStatus.PENDING)
                .completedAt(LocalDateTime.now())
                .total(new BigDecimal("300.00"))
                .build());

        DailyLogResponseDTO created = service.create(new CreateDailyLogRequestDTO(
                exchangeInvoice.getId(),
                List.of(productLine("PIEZA CAMBIARIA", "75.00", 4, "300.00")),
                List.of(),
                null));

        assertThat(created.saleId()).isEqualTo(exchangeInvoice.getId());
    }

    /** Una venta, un registro: lo garantiza uq_daily_log_sale. */
    @Test
    void aSaleCannotBeLoggedTwice() {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "PIEZA UNICA");
        Sale sale = completedSale(workshop);
        CreateDailyLogRequestDTO requestDTO = new CreateDailyLogRequestDTO(
                sale.getId(),
                List.of(productLine("PIEZA UNICA", "75.00", 4, "300.00")),
                List.of(),
                null);

        service.create(requestDTO);

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(EntityAlreadyExistsException.class);
    }

    /**
     * El mismo taller puede levantar varios registros el mismo dia, uno por
     * factura. Antes de V26 el esquema lo prohibia con UNIQUE(workshop_id,
     * performed_date).
     */
    @Test
    void aWorkshopCanLogSeveralSalesOnTheSameDay() {
        Workshop workshop = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "PIEZA REPETIBLE");

        for (int index = 0; index < 3; index++) {
            service.create(new CreateDailyLogRequestDTO(
                    completedSale(workshop).getId(),
                    List.of(productLine("PIEZA REPETIBLE", "75.00", 4, "300.00")),
                    List.of(),
                    null));
        }

        assertThat(service.getAll(new SearchDailyLogRequestDTO(
                null, null, LocalDate.now(), LocalDate.now())))
                .hasSize(3);
    }

    // ── Aislamiento por taller ───────────────────────────────────

    @Test
    void aLogFromAnotherWorkshopIsNeitherListedNorReadable() {
        Workshop other = authenticatedWorkshop();
        product(FamilyCategory.NORMAL, "PIEZA AJENA");
        DailyLogResponseDTO foreign = service.create(new CreateDailyLogRequestDTO(
                completedSale(other).getId(),
                List.of(productLine("PIEZA AJENA", "75.00", 4, "300.00")),
                List.of(),
                null));

        authenticatedWorkshop();

        assertThat(service.getAll(new SearchDailyLogRequestDTO(null, null, null, null)))
                .extracting(DailyLogResponseDTO::id)
                .doesNotContain(foreign.id());
        assertThatThrownBy(() -> service.get(foreign.id()))
                .isInstanceOf(EntityNotFoundException.class);
    }

}
