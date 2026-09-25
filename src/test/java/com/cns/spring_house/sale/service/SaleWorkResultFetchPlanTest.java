package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.entity.JobLog;
import com.cns.spring_house.job.repository.JobLogRepository;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.entity.Sale;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.repository.SaleRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workresult.dto.WorkResultSource;
import com.cns.spring_house.workresult.entity.WorkResult;
import com.cns.spring_house.workresult.repository.WorkResultRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El plan de carga de {@code SaleDetail.workResult} en {@code GET /v1/sales/{id}}.
 *
 * Publicar el trabajo que respalda cada linea abrio un riesgo concreto: la
 * asociacion es LAZY, asi que resolver su origen linea por linea significaba una
 * consulta a `work_result` por cada detalle. Con veinte lineas eso son veinte
 * viajes a la base para pintar una factura, y crece con la factura: el patron
 * N+1 de manual.
 *
 * Aqui no se razona sobre ello, se MIDE. Se interceptan las sentencias que
 * Hibernate manda de verdad a PostgreSQL —el mismo `StatementInspector` que ya
 * usan las pruebas de consulta de cartera— y se cuenta cuantas tocan
 * `work_result` al leer una venta de 1, 10 y 50 lineas.
 *
 * La invariante es semantica, no un numero exacto de SQL: lo que no puede pasar
 * es que el conteo CREZCA con el numero de lineas. Un dia el plan de carga puede
 * cambiar de forma legitima; volver a ser lineal en N, no.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleWorkResultFetchPlanTest {

    private static final String TEMPORARY_DATABASE = "sale_work_result_fetch_it";

    /**
     * Margen para diferencias de plan que NO son N+1: una consulta suelta de mas
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
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add(
                "spring.jpa.properties.hibernate.session_factory.statement_inspector",
                RecordingInspector.class::getName);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Medicion ─────────────────────────────────────────────────

    /**
     * Una sentencia que va A BUSCAR filas de `work_result`, no una que solo
     * menciona su clave ajena.
     *
     * La diferencia importa: el SELECT de `sale_detail` nombra `work_result_id`
     * en su lista de columnas, asi que buscar la palabra suelta contaba como
     * "consulta al trabajo" algo que no lo es e inflaba el resultado con un
     * numero constante. Lo que se vigila es el viaje de verdad a la tabla.
     */
    private static boolean readsWorkResultTable(String sql) {
        return sql.startsWith("select") && sql.contains("from work_result");
    }

    /** Sentencias que consultan `work_result` durante la lectura de la venta. */
    private long workResultSelectsWhenReading(UUID saleId) {
        STATEMENTS.clear();
        service.get(saleId);
        return STATEMENTS.stream()
                .filter(SaleWorkResultFetchPlanTest::readsWorkResultTable)
                .count();
    }

    private long statementsWhenReading(UUID saleId) {
        STATEMENTS.clear();
        service.get(saleId);
        return STATEMENTS.size();
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

    private Inventory inventory(Workshop workshop, Product product, int stock) {
        return inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(workshop)
                .price(new BigDecimal("100.00"))
                .cost(new BigDecimal("10.00"))
                .stock(stock)
                .minStock(0)
                .build());
    }

    private InventoryItem soldItem(Inventory inventory) {
        return itemRepository.save(InventoryItem.builder()
                .inventory(inventory)
                .status(InventoryItemStatus.SOLD)
                .build());
    }

    /**
     * Venta ya emitida con {@code lines} lineas iguales, cada una con su unidad
     * fisica de salida y, si se pide, con el trabajo de transformacion que la
     * respalda.
     *
     * Las dos variantes existen para poder RESTAR: comparar una venta con
     * trabajos contra otra identica sin ellos aisla lo que cuesta exactamente
     * `workResult`, sin mezclarlo con lo que ya costaba leer producto e item.
     */
    private Sale saleWithTransformationLines(
            Workshop workshop, int lines, boolean withWorkResult) {
        Product output = product(FamilyCategory.SPECIAL);
        Inventory inventory = inventory(workshop, output, lines);

        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .total(BigDecimal.ZERO)
                .build();

        for (int index = 0; index < lines; index++) {
            InventoryItem item = soldItem(inventory);
            SaleDetail detail = SaleDetail.builder()
                    .product(output)
                    .item(item)
                    .quantity(1)
                    .price(new BigDecimal("380.00"))
                    .subTotal(new BigDecimal("380.00"))
                    .build();
            if (withWorkResult) {
                detail.setWorkResult(workResultRepository.save(WorkResult.builder()
                        .workshop(workshop)
                        .item(item)
                        .build()));
            }
            sale.addDetail(detail);
        }

        return saleRepository.save(sale);
    }

    private Sale saleWithJobLine(Workshop workshop) {
        Product jobProduct = product(FamilyCategory.JOB);
        Job job = jobRepository.save(Job.builder()
                .product(jobProduct)
                .workshop(workshop)
                .price(new BigDecimal("150.00"))
                .build());
        JobLog jobLog = jobLogRepository.save(JobLog.builder()
                .employee(employee(workshop))
                .job(job)
                .performedDate(LocalDateTime.now())
                .build());
        WorkResult workResult = workResultRepository.save(WorkResult.builder()
                .workshop(workshop)
                .jobLog(jobLog)
                .build());

        Sale sale = Sale.builder()
                .taxMode(TaxMode.CF)
                .workshop(workshop)
                .employee(employee(workshop))
                .status(SaleStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .total(BigDecimal.ZERO)
                .build();
        SaleDetail detail = SaleDetail.builder()
                .product(jobProduct)
                .quantity(1)
                .price(new BigDecimal("150.00"))
                .subTotal(new BigDecimal("150.00"))
                .build();
        detail.setWorkResult(workResult);
        sale.addDetail(detail);

        return saleRepository.save(sale);
    }

    // ── La invariante ────────────────────────────────────────────

    /**
     * La prueba que falla si `workResult` vuelve a resolverse linea por linea.
     *
     * Con carga perezosa por fila, leer cincuenta lineas manda cincuenta SELECT
     * a `work_result`; con el plan de carga correcto manda los mismos que para
     * una sola.
     */
    @Test
    void readingASaleNeverQueriesWorkResultOncePerLine() {
        Workshop workshop = authenticatedWorkshop();
        UUID one = saleWithTransformationLines(workshop, 1, true).getId();
        UUID ten = saleWithTransformationLines(workshop, 10, true).getId();
        UUID fifty = saleWithTransformationLines(workshop, 50, true).getId();

        long forOne = workResultSelectsWhenReading(one);
        long forTen = workResultSelectsWhenReading(ten);
        long forFifty = workResultSelectsWhenReading(fifty);

        assertThat(forTen)
                .as("consultas a work_result con 10 lineas (con 1: %d)", forOne)
                .isLessThanOrEqualTo(forOne + CONSTANT_TOLERANCE);
        assertThat(forFifty)
                .as("consultas a work_result con 50 lineas (con 1: %d)", forOne)
                .isLessThanOrEqualTo(forOne + CONSTANT_TOLERANCE);
    }

    /**
     * Y lo mismo mirando el total de sentencias, restando lo que la venta ya
     * costaba sin trabajos.
     *
     * Esta resta es la que aisla el cambio: leer producto e item de cada linea
     * tiene su propio coste, anterior a este trabajo y ajeno a el, y sumarlo
     * dentro escondaria justo lo que se quiere vigilar.
     */
    @Test
    void theCostOfPublishingTheWorkResultDoesNotGrowWithTheNumberOfLines() {
        Workshop workshop = authenticatedWorkshop();

        long withOne = statementsWhenReading(
                saleWithTransformationLines(workshop, 1, true).getId());
        long withoutOne = statementsWhenReading(
                saleWithTransformationLines(workshop, 1, false).getId());

        long withFifty = statementsWhenReading(
                saleWithTransformationLines(workshop, 50, true).getId());
        long withoutFifty = statementsWhenReading(
                saleWithTransformationLines(workshop, 50, false).getId());

        long costOfOne = withOne - withoutOne;
        long costOfFifty = withFifty - withoutFifty;

        assertThat(costOfFifty)
                .as("sentencias atribuibles a workResult con 50 lineas (con 1: %d)", costOfOne)
                .isLessThanOrEqualTo(costOfOne + CONSTANT_TOLERANCE);
    }

    // ── Y el dato sigue siendo correcto ──────────────────────────

    @Test
    void everyLineStillCarriesItsOwnWorkResult() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = saleWithTransformationLines(workshop, 10, true);

        SaleResponseDTO response = service.get(sale.getId());

        assertThat(response.details()).hasSize(10);
        assertThat(response.details())
                .allSatisfy(detail -> {
                    assertThat(detail.workResult()).isNotNull();
                    assertThat(detail.workResult().source())
                            .isEqualTo(WorkResultSource.TRANSFORMATION);
                });
        // Cada linea con el SUYO, no cincuenta copias del primero.
        assertThat(response.details())
                .extracting(detail -> detail.workResult().id())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        sale.getDetails().stream()
                                .map(detail -> detail.getWorkResult().getId())
                                .toList());
    }

    /** Y las lineas sin trabajo detras siguen llegando en null. */
    @Test
    void linesWithoutWorkResultStillTravelAsNull() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = saleWithTransformationLines(workshop, 5, false);

        assertThat(service.get(sale.getId()).details())
                .hasSize(5)
                .allSatisfy(detail -> assertThat(detail.workResult()).isNull());
    }

    /** El origen sale del trabajo, nunca de la familia del producto. */
    @Test
    void aJobLineIsReportedAsJobAndATransformationAsTransformation() {
        Workshop workshop = authenticatedWorkshop();
        Sale jobSale = saleWithJobLine(workshop);
        Sale transformationSale = saleWithTransformationLines(workshop, 1, true);

        SaleDetailResponseDTO jobLine = service.get(jobSale.getId()).details().get(0);
        SaleDetailResponseDTO transformationLine =
                service.get(transformationSale.getId()).details().get(0);

        assertThat(jobLine.workResult().source()).isEqualTo(WorkResultSource.JOB);
        assertThat(transformationLine.workResult().source())
                .isEqualTo(WorkResultSource.TRANSFORMATION);
    }

    /**
     * El listado paga el mismo N+1 pero multiplicado por el numero de ventas, asi
     * que tambien se vigila: leer diez ventas de cinco lineas no puede costar
     * cincuenta consultas a `work_result`.
     */
    @Test
    void listingSalesNeverQueriesWorkResultOncePerLineEither() {
        Workshop workshop = authenticatedWorkshop();
        for (int sale = 0; sale < 5; sale++) {
            saleWithTransformationLines(workshop, 5, true);
        }

        STATEMENTS.clear();
        List<SaleResponseDTO> sales = service.getAll(new SearchSaleRequestDTO(
                null, null, null, null, null, null, null, null, null, null, null, null));
        long workResultSelects = STATEMENTS.stream()
                .filter(SaleWorkResultFetchPlanTest::readsWorkResultTable)
                .count();

        assertThat(sales).hasSize(5);
        assertThat(sales).allSatisfy(sale -> assertThat(sale.details()).hasSize(5));
        // 25 lineas con trabajo: con carga perezosa serian 25 consultas.
        assertThat(workResultSelects)
                .as("consultas a work_result al listar 5 ventas de 5 lineas")
                .isLessThanOrEqualTo(CONSTANT_TOLERANCE);
    }

    /** Y la venta no se duplica al traer el trabajo en la misma consulta. */
    @Test
    void joiningTheWorkResultNeverDuplicatesLines() {
        Workshop workshop = authenticatedWorkshop();
        Sale sale = saleWithTransformationLines(workshop, 10, true);

        SaleResponseDTO response = service.get(sale.getId());

        assertThat(response.details()).hasSize(10);
        assertThat(response.details())
                .extracting(SaleDetailResponseDTO::id)
                .doesNotHaveDuplicates();
    }

}
