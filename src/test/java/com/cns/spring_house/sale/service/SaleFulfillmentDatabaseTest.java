package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.cashclosure.dto.CashClosureResponseDTO;
import com.cns.spring_house.cashclosure.service.CashClosureService;
import com.cns.spring_house.dashboard.dto.ProductPerformanceDTO;
import com.cns.spring_house.dashboard.service.AdminDashboardService;
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.job.entity.Job;
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.BlockFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.DispatchSaleRequestDTO;
import com.cns.spring_house.sale.dto.FulfillTransformationRequestDTO;
import com.cns.spring_house.sale.dto.FulfillmentSaleProgressDTO;
import com.cns.spring_house.sale.dto.FulfillmentSourceDTO;
import com.cns.spring_house.sale.dto.FulfillmentTaskResponseDTO;
import com.cns.spring_house.sale.dto.ReleaseFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.RequestSpecificationDTO;
import com.cns.spring_house.sale.dto.SaleDashboardStatsDTO;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SaveForLaterRequestDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.dto.TopProductDTO;
import com.cns.spring_house.sale.dto.UpdateRequestPriceDTO;
import com.cns.spring_house.sale.entity.FulfillmentArea;
import com.cns.spring_house.sale.entity.FulfillmentBlockReason;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.PaymentStatus;
import com.cns.spring_house.sale.entity.SaleFulfillmentStatus;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.shared.barcode.BarcodeIdentity;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.service.TransformationService;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workresult.dto.SearchWorkResultRequestDTO;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.entity.WorkResultStatus;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * La comanda de punta a punta contra PostgreSQL: el vendedor pide, el envio
 * decide la ruta con el stock real, Control entrega, el Operador fabrica o
 * ejecuta, y la venta solo se factura cuando todo esta cumplido.
 *
 * Sin transaccion de prueba: cada llamada usa la frontera transaccional del
 * propio servicio, que es lo que garantiza que un fallo a mitad no deja stock
 * reservado ni solicitudes a medias. Cada rol es una cuenta real con su
 * empleado, porque quien entrega o fabrica queda registrado.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleFulfillmentDatabaseTest {

    private static final String TEMPORARY_DATABASE = "sale_fulfillment_it";
    private static final BigDecimal PRICE = new BigDecimal("500.00");
    private static final BigDecimal COST = new BigDecimal("100.00");
    private static final String PLATE = "P123ABC";

    /** Diferencia de plan que NO es un N+1 (misma tolerancia que SaleWorkResultFetchPlanTest). */
    private static final int CONSTANT_TOLERANCE = 3;
    /** Tope corto de espera al fabricar, para que la prueba del cerrojo retenido no tarde. */
    private static final int TRANSFORM_TIMEOUT_SECONDS = 3;

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
    private SaleService saleService;

    @Autowired
    private SaleFulfillmentService fulfillmentService;

    @Autowired
    private WorkResultService workResultService;

    @Autowired
    private TransformationService transformationService;

    @Autowired
    private CashClosureService cashClosureService;

    @Autowired
    private SaleDashboardService saleDashboardService;

    @Autowired
    private AdminDashboardService adminDashboardService;

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
    private BrandRepository brandRepository;

    @Autowired
    private ModelRepository modelRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private InventoryAcquisitionRepository acquisitionRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Las pruebas de concurrencia abren varias transacciones a la vez; el
        // mismo tope que FifoConcurrencyDatabaseTest.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("app.fulfillment.transform-timeout-seconds", () -> TRANSFORM_TIMEOUT_SECONDS);
        registry.add(
                "spring.jpa.properties.hibernate.session_factory.statement_inspector",
                RecordingInspector.class::getName);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── 51. Pieza especial con stock: la entrega Control ─────────

    @Test
    void aStockedSpecialRequestIsReleasedByControlWithTheExactUnit() {
        Shop shop = shop();
        Product leaf = leaf(shop, 120, 2);
        List<InventoryItem> units = stocked(shop, leaf, 2);
        long transformationsBefore = transformations();

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Carlos");
        sale = saleService.addDetail(sale.id(), specialRequest(leaf, 1, "Revisar que no este oxidada"));

        // Agregar la reserva: la unidad ya es de esta venta, aunque no se haya
        // enviado. Reservada con la regla de siempre: SOLD, fuera del stock y
        // costeada.
        SaleDetailResponseDTO line = onlyLine(sale);
        assertThat(line.fulfillment().status()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(line.fulfillment().type()).isEqualTo(FulfillmentType.STOCK_RELEASE);
        assertThat(line.fulfillment().area()).isEqualTo(FulfillmentArea.CONTROL);
        UUID reserved = line.item().id();
        assertThat(itemStatus(reserved)).isEqualTo("SOLD");
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(costTotalOf(line.id())).isEqualByComparingTo(COST);
        assertThat(sale.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.NONE);

        // Enviar la manda a Control con la MISMA unidad, sin reservar otra.
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        line = onlyLine(sale);
        assertThat(line.fulfillment().type()).isEqualTo(FulfillmentType.STOCK_RELEASE);
        assertThat(line.fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);
        assertThat(sale.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.IN_PROGRESS);
        assertThat(line.item().id()).isEqualTo(reserved);
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(costTotalOf(line.id())).isEqualByComparingTo(COST);
        assertThat(transformations()).isEqualTo(transformationsBefore);

        // Control ve el pedido con su especificacion y el stock real.
        as(shop.control());
        FulfillmentTaskResponseDTO task = onlyTask(
                fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null), line.id());
        assertThat(task.customerName()).isEqualToIgnoringCase("Carlos");
        assertThat(task.product().id()).isEqualTo(leaf.getId());
        assertThat(task.product().familyName()).isEqualTo("HOJA DE RESORTE");
        assertThat(task.product().modelId()).isEqualTo(shop.model().getId());
        assertThat(task.product().brandName()).isEqualTo(shop.model().getBrand().getName());
        assertThat(task.product().attributes()).containsEntry("largo", 120);
        assertThat(task.instructions()).isEqualTo("Revisar que no este oxidada");
        assertThat(task.availableStock()).isEqualTo(1);
        assertThat(task.itemBarcode()).isEqualTo(BarcodeIdentity.item(reserved));

        // Puede entregar la reservada o cualquier otra disponible del producto.
        List<UUID> eligible = fulfillmentService.getEligibleItems(line.id()).stream()
                .map(InventoryItemResponseDTO::id)
                .toList();
        UUID other = units.stream().map(InventoryItem::getId)
                .filter(id -> !id.equals(reserved)).findFirst().orElseThrow();
        assertThat(eligible).containsExactlyInAnyOrder(reserved, other);

        task = fulfillmentService.release(line.id(), new ReleaseFulfillmentRequestDTO(other));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(task.itemId()).isEqualTo(other);
        assertThat(task.itemBarcode()).isEqualTo(BarcodeIdentity.item(other));
        // La entregada sale; la reservada vuelve. El stock no se mueve: es una
        // unidad del mismo inventario por otra.
        assertThat(itemStatus(other)).isEqualTo("SOLD");
        assertThat(itemStatus(reserved)).isEqualTo("AVAILABLE");
        assertThat(stockOf(leaf, shop)).isEqualTo(1);

        // Entregar dos veces no es posible.
        UUID releasedLine = line.id();
        assertThatThrownBy(() -> fulfillmentService.release(
                releasedLine, new ReleaseFulfillmentRequestDTO(null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue entregada");

        as(shop.seller());
        sale = saleService.get(sale.id());
        assertThat(sale.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.READY_TO_COMPLETE);
        assertThat(onlyLine(sale).item().id()).isEqualTo(other);
        assertThat(saleService.getReady("carl")).extracting(SaleResponseDTO::id).contains(sale.id());

        SaleResponseDTO completed = completeAsCf(sale.id());
        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.details()).hasSize(1);
        assertThat(completed.total()).isEqualByComparingTo(PRICE);
        assertThat(transformations()).isEqualTo(transformationsBefore);
    }

    // ── 52. Pieza especial sin stock: la fabrica el Operador ─────

    @Test
    void anOutOfStockSpecialRequestIsFabricatedAndReturnsToTheSaleByItself() {
        Shop shop = shop();
        Product target = leaf(shop, 100, 2);
        emptyInventory(shop, target);
        Product longer = leaf(shop, 200, 3);
        InventoryItem source = stocked(shop, longer, 1).get(0);

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Ana");
        // Sin stock sigue siendo elegible.
        sale = saleService.addDetail(sale.id(), specialRequest(target, 1, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        SaleDetailResponseDTO line = onlyLine(sale);
        assertThat(line.fulfillment().type()).isEqualTo(FulfillmentType.TRANSFORMATION);
        assertThat(line.fulfillment().area()).isEqualTo(FulfillmentArea.OPERATOR);
        assertThat(line.item()).isNull();
        // No hay Control que liberar: nada aparece en su cola.
        as(shop.control());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null)).isEmpty();

        as(shop.operator());
        FulfillmentTaskResponseDTO task = onlyTask(
                fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), line.id());
        assertThat(task.customerName()).isEqualToIgnoringCase("Ana");
        assertThat(task.product().attributes()).containsEntry("largo", 100);

        // El operador elige la pieza de origen entre las compatibles.
        FulfillmentSourceDTO offered = fulfillmentService.getSources(line.id(), null).stream()
                .filter(candidate -> candidate.item().id().equals(source.getId()))
                .findFirst().orElseThrow();
        assertThat(offered.sameModel()).isTrue();
        assertThat(offered.acceptsOutputCurlCount()).isFalse();
        assertThat(offered.remainderLength()).isEqualTo(100.0);
        assertThat(offered.remainderWithoutCurls()).isTrue();

        fulfillmentService.start(line.id());
        task = fulfillmentService.transform(line.id(),
                new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(task.transformationId()).isNotNull();
        UUID output = task.itemId();
        assertThat(output).isNotNull().isNotEqualTo(source.getId());
        assertThat(task.itemBarcode()).isEqualTo(BarcodeIdentity.item(output));

        // La pieza de origen se consumio; la fabricada nace con identidad propia
        // y queda ya en la venta.
        assertThat(itemStatus(source.getId())).isEqualTo("CONSUMED");
        assertThat(itemStatus(output)).isEqualTo("SOLD");
        assertThat(productOfItem(output)).isEqualTo(target.getId());
        assertThat(stockOf(target, shop)).isZero();

        as(shop.seller());
        sale = saleService.get(sale.id());
        line = onlyLine(sale);
        assertThat(line.item().id()).isEqualTo(output);
        assertThat(line.workResult()).isNotNull();
        assertThat(sale.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.READY_TO_COMPLETE);

        SaleResponseDTO completed = completeAsCf(sale.id());
        // Se factura la pieza fabricada, una vez; la de origen nunca.
        assertThat(completed.details()).hasSize(1);
        assertThat(completed.total()).isEqualByComparingTo(PRICE);
        assertThat(workResultStatusOfItem(output)).isEqualTo("SOLD");
    }

    // ── 53. Pieza que todavia no existe en el catalogo ───────────

    @Test
    void aNewValidSpecificationCanBeRequestedAndIsFabricatedAsThatProduct() {
        Shop shop = shop();
        Family hoja = seededFamily("HR");
        InventoryItem source = stocked(shop, leaf(shop, 200, 3), 1).get(0);
        Map<String, Object> wanted = attributes(
                "largo", 87, "tieneOrificio", false, "cantidadColochos", 1);
        assertThat(productWith(hoja, shop.model(), wanted)).isZero();

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Luis");
        sale = saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), wanted, 1, PRICE, "Hoja de resorte", null));
        SaleDetailResponseDTO line = onlyLine(sale);
        UUID requested = line.product().id();
        // Ya figura en la cuenta, con su importe, aunque la pieza no exista.
        assertThat(line.item()).isNull();
        assertThat(sale.total()).isEqualByComparingTo(PRICE);
        // El producto existe ahora en el catalogo, sin inventario ni stock.
        assertThat(productWith(hoja, shop.model(), wanted)).isEqualTo(1);
        assertThat(inventoryCount(requested)).isZero();

        // Pedir lo mismo otra vez no duplica el catalogo.
        sale = saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), wanted, 1, PRICE, null, null));
        assertThat(productWith(hoja, shop.model(), wanted)).isEqualTo(1);
        UUID duplicate = sale.details().stream()
                .filter(detail -> !detail.id().equals(line.id()))
                .findFirst().orElseThrow().id();
        saleService.removeDetail(duplicate);

        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        assertThat(onlyLine(sale).fulfillment().type()).isEqualTo(FulfillmentType.TRANSFORMATION);

        as(shop.operator());
        FulfillmentTaskResponseDTO task = fulfillmentService.transform(line.id(),
                new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(productOfItem(task.itemId())).isEqualTo(requested);
        assertThat(familyOfProduct(requested)).isEqualTo(hoja.getId());
        assertThat(modelOfProduct(requested)).isEqualTo(shop.model().getId());
        assertThat(inventoryCount(requested)).isEqualTo(1);

        as(shop.seller());
        SaleResponseDTO fabricated = saleService.get(sale.id());
        assertThat(onlyLine(fabricated).id()).isEqualTo(line.id());
        assertThat(onlyLine(fabricated).item().id()).isEqualTo(task.itemId());
        assertThat(fabricated.total()).isEqualByComparingTo(PRICE);
    }

    @Test
    void anInvalidSpecificationIsRejectedWithTheCatalogRules() {
        Shop shop = shop();
        Family hoja = seededFamily("HR");
        long productsBefore = products();

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Mario");

        // Tipo equivocado.
        assertThatThrownBy(() -> saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(),
                attributes("largo", "mucho", "tieneOrificio", false), 1, PRICE, null, null)))
                .isInstanceOf(ValidationException.class);
        // Caracteristica que la familia no declara.
        assertThatThrownBy(() -> saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(),
                attributes("largo", 90, "tieneOrificio", false, "color", "rojo"), 1, PRICE, null, null)))
                .isInstanceOf(ValidationException.class);
        // Falta una obligatoria.
        assertThatThrownBy(() -> saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), attributes("largo", 90), 1, PRICE, null, null)))
                .isInstanceOf(ValidationException.class);
        // Medidas con orificio que no suman el largo.
        assertThatThrownBy(() -> saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(),
                attributes("largo", 90, "tieneOrificio", true, "largoIzquierdo", 40, "largoDerecho", 40),
                1, PRICE, null, null)))
                .isInstanceOf(ValidationException.class);
        // La hoja exige modelo.
        assertThatThrownBy(() -> saleService.requestSpecification(sale.id(), new RequestSpecificationDTO(
                hoja.getId(), null, attributes("largo", 90, "tieneOrificio", false), 1, PRICE, null, null)))
                .isInstanceOf(ValidationException.class);

        assertThat(products()).isEqualTo(productsBefore);
        assertThat(saleService.get(sale.id()).details()).isEmpty();
    }

    // ── 54. Stock parcial ────────────────────────────────────────

    @Test
    void threeRequestedWithOneInStockSplitsOneToControlAndTwoToTheOperator() {
        Shop shop = shop();
        Product leaf = leaf(shop, 150, 2);
        stocked(shop, leaf, 1);

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Pedro");
        sale = saleService.addDetail(sale.id(), specialRequest(leaf, 3, null));
        assertThat(sale.details()).hasSize(3);
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));

        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        assertThat(sale.details())
                .extracting(detail -> detail.fulfillment().type())
                .containsExactlyInAnyOrder(
                        FulfillmentType.STOCK_RELEASE,
                        FulfillmentType.TRANSFORMATION,
                        FulfillmentType.TRANSFORMATION);
        // Cada unidad se cobra una vez: el total no cambia por repartirla.
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));
        assertThat(stockOf(leaf, shop)).isZero();
    }

    // ── 55. Servicio ─────────────────────────────────────────────

    @Test
    void aServiceRequestIsExecutedByTheOperatorAndReturnsToTheSale() {
        Shop shop = shop();
        Product installation = job(shop, "INSTALACION DE RESORTE");

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Rosa");
        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("150.00"), null,
                "Instalar del lado izquierdo"));
        SaleDetailResponseDTO line = onlyLine(sale);
        assertThat(line.fulfillment().type()).isEqualTo(FulfillmentType.SERVICE);
        assertThat(line.fulfillment().status()).isEqualTo(FulfillmentStatus.PENDING);

        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        assertThat(onlyLine(sale).fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);
        UUID saleId = sale.id();
        assertThatThrownBy(() -> completeAsCf(saleId))
                .isInstanceOf(ValidationException.class);

        as(shop.operator());
        FulfillmentTaskResponseDTO task = onlyTask(
                fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), line.id());
        assertThat(task.product().name()).isEqualTo(installation.getName());
        assertThat(task.instructions()).isEqualTo("Instalar del lado izquierdo");
        assertThat(task.customerName()).isEqualToIgnoringCase("Rosa");

        task = fulfillmentService.completeService(line.id());
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(task.jobLogId()).isNotNull();
        assertThat(task.assignedTo()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT employee_id FROM job_log WHERE id = ?", UUID.class, task.jobLogId()))
                .isEqualTo(shop.operatorEmployee().getId());

        as(shop.seller());
        sale = saleService.get(sale.id());
        assertThat(onlyLine(sale).workResult()).isNotNull();
        SaleResponseDTO completed = completeAsCf(sale.id());
        assertThat(completed.details()).hasSize(1);
        assertThat(completed.total()).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM work_result WHERE job_log_id = ?", String.class, task.jobLogId()))
                .isEqualTo("SOLD");
    }

    // ── 56. Comanda mixta: 0/3 -> 3/3 ────────────────────────────

    @Test
    void aMixedOrderOnlyBecomesReadyWhenEveryRequestIsFulfilled() {
        Shop shop = shop();
        Product stockedLeaf = leaf(shop, 130, 2);
        stocked(shop, stockedLeaf, 1);
        Product missingLeaf = leaf(shop, 90, 2);
        emptyInventory(shop, missingLeaf);
        InventoryItem source = stocked(shop, leaf(shop, 180, 1), 1).get(0);
        Product installation = job(shop, "INSTALACION");

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Juan Perez");
        sale = saleService.addDetail(sale.id(), specialRequest(stockedLeaf, 1, null));
        sale = saleService.addDetail(sale.id(), specialRequest(missingLeaf, 1, null));
        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("150.00"), null, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID saleId = sale.id();

        UUID controlLine = lineOf(sale, FulfillmentType.STOCK_RELEASE);
        UUID transformationLine = lineOf(sale, FulfillmentType.TRANSFORMATION);
        UUID serviceLine = lineOf(sale, FulfillmentType.SERVICE);

        assertReadiness(shop, saleId, SaleFulfillmentStatus.IN_PROGRESS, 0);

        as(shop.control());
        fulfillmentService.release(controlLine, new ReleaseFulfillmentRequestDTO(null));
        assertReadiness(shop, saleId, SaleFulfillmentStatus.IN_PROGRESS, 1);

        as(shop.operator());
        // 180 -> 90 deja un sobrante de 90: sus colochos los decide el operador.
        fulfillmentService.transform(transformationLine,
                new FulfillTransformationRequestDTO(source.getId(), null));
        assertReadiness(shop, saleId, SaleFulfillmentStatus.IN_PROGRESS, 2);

        as(shop.operator());
        fulfillmentService.completeService(serviceLine);
        assertReadiness(shop, saleId, SaleFulfillmentStatus.READY_TO_COMPLETE, 3);

        as(shop.seller());
        assertThat(saleService.getReady("juan")).extracting(SaleResponseDTO::id).contains(saleId);
        SaleResponseDTO completed = completeAsCf(saleId);
        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.details()).hasSize(3);
        // Las lineas vuelven siempre en el orden en que se pidieron, aunque el
        // taller las haya cumplido en otro orden.
        assertThat(completed.details())
                .extracting(detail -> detail.fulfillment().type())
                .containsExactly(
                        FulfillmentType.STOCK_RELEASE,
                        FulfillmentType.TRANSFORMATION,
                        FulfillmentType.SERVICE);
        assertThat(completed.total()).isEqualByComparingTo(
                PRICE.add(PRICE).add(new BigDecimal("150.00")));
        assertThat(saleService.getReady(null)).extracting(SaleResponseDTO::id).doesNotContain(saleId);
    }

    // ── La cuenta muestra lo pedido desde que se pide ───────────

    /**
     * Como en un restaurante: el plato esta en la cuenta desde que se pide y la
     * cocina solo cambia si ya esta listo. La pieza a fabricar es la MISMA linea
     * de principio a fin, con el mismo importe; fabricarla no agrega otra.
     */
    @Test
    void theOrderedPieceIsOnTheAccountFromTheStartAndFabricationOnlyMakesItReady() {
        Shop shop = shop();
        Product target = leaf(shop, 120, 2);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 240, 2), 1).get(0);
        BigDecimal price = new BigDecimal("320.00");

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Mesa 4");
        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                target.getId(), null, null, 1, price, "Hoja de resorte Toyota Tundra 120", null));

        // En la cuenta en cuanto se pide, sin nada fisico todavia.
        SaleDetailResponseDTO ordered = onlyLine(sale);
        UUID lineId = ordered.id();
        assertThat(ordered.subTotal()).isEqualByComparingTo(price);
        assertThat(ordered.item()).isNull();
        assertThat(ordered.workResult()).isNull();
        assertThat(sale.total()).isEqualByComparingTo(price);
        SaleResponseDTO account = saleService.get(sale.id());
        assertThat(onlyLine(account).id()).isEqualTo(lineId);
        assertThat(account.total()).isEqualByComparingTo(price);

        // Estar en la cuenta no la hace cobrable: sin cumplir, no se completa.
        UUID saleId = sale.id();
        assertThatThrownBy(() -> completeAsCf(saleId)).isInstanceOf(ValidationException.class);

        sale = saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        assertThat(onlyLine(sale).id()).isEqualTo(lineId);
        assertThat(onlyLine(sale).fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);
        assertThat(sale.total()).isEqualByComparingTo(price);

        as(shop.operator());
        fulfillmentService.start(lineId);
        as(shop.seller());
        account = saleService.get(saleId);
        assertThat(onlyLine(account).id()).isEqualTo(lineId);
        assertThat(onlyLine(account).fulfillment().status()).isEqualTo(FulfillmentStatus.IN_PROGRESS);
        assertThat(account.total()).isEqualByComparingTo(price);
        assertThatThrownBy(() -> completeAsCf(saleId)).isInstanceOf(ValidationException.class);

        // Fabricar resuelve la MISMA linea: le pone la pieza, su trabajo y su estado.
        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                lineId, new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(done.id()).isEqualTo(lineId);

        as(shop.seller());
        account = saleService.get(saleId);
        SaleDetailResponseDTO ready = onlyLine(account);
        assertThat(ready.id()).isEqualTo(lineId);
        assertThat(ready.fulfillment().status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(ready.item().id()).isEqualTo(done.itemId());
        assertThat(ready.workResult()).isNotNull();
        assertThat(ready.fulfillment().transformationId()).isEqualTo(done.transformationId());
        assertThat(linesOfSale(saleId)).isEqualTo(1);
        // Mismo importe y misma descripcion: fabricar no cambia la cuenta.
        assertThat(ready.price()).isEqualByComparingTo(price);
        assertThat(ready.subTotal()).isEqualByComparingTo(price);
        assertThat(ready.invoiceDescription()).isEqualTo("Hoja de resorte Toyota Tundra 120");
        assertThat(account.total()).isEqualByComparingTo(price);
        // La pieza de origen nunca es una linea de la venta.
        assertThat(linesWithItem(source.getId())).isZero();

        // La factura lleva la pieza fabricada una sola vez.
        SaleResponseDTO completed = completeAsCf(saleId);
        assertThat(completed.details()).singleElement()
                .satisfies(line -> assertThat(line.id()).isEqualTo(lineId));
        assertThat(completed.total()).isEqualByComparingTo(price);
        assertThat(linesOfSale(saleId)).isEqualTo(1);
    }

    /**
     * La pieza pedida no fija los colochos y el operador los decide: la
     * transformacion resuelve el producto equivalente que los incluye y la
     * linea pasa a esa pieza. El cliente sigue viendo en su cuenta y en su
     * factura lo que pidio, con el mismo importe.
     */
    @Test
    void aPieceResolvedToAnEquivalentProductKeepsTheOrderedNameOnTheAccount() {
        Shop shop = shop();
        Product target = productRepository.save(Product.builder()
                .family(seededFamily("HR"))
                .model(shop.model())
                .name("HOJA TUNDRA " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes("largo", 110, "tieneOrificio", false))
                .build());
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 220, 2), 1).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Equivalente").id(),
                curlRequest(target, true, 2));
        UUID lineId = onlyLine(sale).id();
        assertThat(onlyLine(sale).invoiceDescription()).isNull();
        saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));

        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                lineId, new FulfillTransformationRequestDTO(source.getId(), 2));
        assertThat(done.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        UUID produced = productOfItem(done.itemId());
        assertThat(produced).isNotEqualTo(target.getId());

        as(shop.seller());
        SaleResponseDTO account = saleService.get(sale.id());
        SaleDetailResponseDTO line = onlyLine(account);
        assertThat(line.id()).isEqualTo(lineId);
        assertThat(line.product().id()).isEqualTo(produced);
        assertThat(line.invoiceDescription()).isEqualTo(target.getName());
        assertThat(account.total()).isEqualByComparingTo(PRICE);

        SaleResponseDTO completed = completeAsCf(sale.id());
        assertThat(onlyLine(completed).invoiceDescription()).isEqualTo(target.getName());
        assertThat(completed.total()).isEqualByComparingTo(PRICE);
    }

    /**
     * La solicitud es una fila de sale_detail desde que se pide: la misma fila
     * antes de enviar, en el taller, fabricada y facturada. Solo cambian su
     * cumplimiento y lo que el taller le asigna (la pieza y su trabajo); lo
     * comercial queda como se pidio.
     */
    @Test
    void theRequestedPieceIsTheSameSaleDetailRowFromOrderToInvoice() {
        Shop shop = shop();
        Product target = leaf(shop, 130, 2);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 260, 2), 1).get(0);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Persistencia").id();
        UUID lineId = onlyLine(saleService.addDetail(saleId, new AddSaleDetailRequestDTO(
                target.getId(), null, null, 1, new BigDecimal("320.00"), "Hoja especial", null))).id();

        // En la base antes de enviar, sin nada fisico todavia.
        Map<String, Object> ordered = row(lineId);
        assertThat(ordered.get("sale_id")).isEqualTo(saleId);
        assertThat(ordered.get("product_id")).isEqualTo(target.getId());
        assertThat((BigDecimal) ordered.get("price")).isEqualByComparingTo("320.00");
        assertThat((BigDecimal) ordered.get("sub_total")).isEqualByComparingTo("320.00");
        assertThat(ordered.get("invoice_description")).isEqualTo("Hoja especial");
        assertThat(ordered.get("created_at")).isNotNull();
        assertThat(ordered.get("fulfillment_status")).isEqualTo("PENDING");
        assertThat(ordered.get("item_id")).isNull();
        assertThat(ordered.get("work_result_id")).isNull();
        // Releer la venta, que es lo que hace la caja al recargar, la trae igual.
        assertThat(onlyLine(saleService.get(saleId)).id()).isEqualTo(lineId);

        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        Map<String, Object> queued = row(lineId);
        assertThat(queued.get("fulfillment_type")).isEqualTo("TRANSFORMATION");
        assertThat(queued.get("fulfillment_status")).isEqualTo("QUEUED");

        as(shop.operator());
        fulfillmentService.start(lineId);
        Map<String, Object> working = row(lineId);
        assertThat(working.get("fulfillment_status")).isEqualTo("IN_PROGRESS");
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                lineId, new FulfillTransformationRequestDTO(source.getId(), null));
        Map<String, Object> fabricated = row(lineId);
        assertThat(fabricated.get("fulfillment_status")).isEqualTo("FULFILLED");
        assertThat(fabricated.get("item_id")).isEqualTo(done.itemId());
        assertThat(fabricated.get("work_result_id")).isNotNull();
        assertThat(fabricated.get("fulfillment_transformation_id")).isEqualTo(done.transformationId());

        as(shop.seller());
        completeAsCf(saleId);
        Map<String, Object> invoiced = row(lineId);
        assertThat(invoiced.get("item_id")).isEqualTo(fabricated.get("item_id"));
        assertThat(invoiced.get("work_result_id")).isEqualTo(fabricated.get("work_result_id"));
        assertThat(workResultStatusOfItem(done.itemId())).isEqualTo("SOLD");

        for (Map<String, Object> snapshot : List.of(queued, working, fabricated, invoiced)) {
            assertThat(snapshot.get("id")).isEqualTo(lineId);
            for (String column : List.of(
                    "sale_id", "product_id", "quantity", "price", "sub_total",
                    "invoice_description", "created_at")) {
                assertThat(snapshot.get(column)).as(column).isEqualTo(ordered.get(column));
            }
        }
        assertThat(linesOfSale(saleId)).isEqualTo(1);
        assertThat(linesWithItem(source.getId())).isZero();
    }

    /**
     * Una solicitud al taller es una linea de una venta: la base no admite un
     * cumplimiento sin venta, ni dos lineas cobrando la misma transformacion.
     */
    @Test
    void aWorkshopRequestCannotExistWithoutItsSaleLine() {
        Shop shop = shop();
        Product target = leaf(shop, 125, 2);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 250, 2), 1).get(0);
        UUID line = fabricationRequest(shop, target);
        UUID saleId = saleIdOf(line);
        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), null));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sale_detail (id, sale_id, product_id, quantity, price, sub_total, "
                        + "fulfillment_type, fulfillment_status) "
                        + "VALUES (?, NULL, ?, 1, 200, 200, 'TRANSFORMATION', 'QUEUED')",
                UUID.randomUUID(), target.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sale_id");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO sale_detail (id, sale_id, product_id, quantity, price, sub_total, "
                        + "fulfillment_type, fulfillment_status, fulfillment_transformation_id) "
                        + "VALUES (?, ?, ?, 1, 200, 200, 'TRANSFORMATION', 'FULFILLED', ?)",
                UUID.randomUUID(), saleId, target.getId(), done.transformationId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_sale_detail_fulfillment_transformation");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_detail d LEFT JOIN sale s ON s.id = d.sale_id "
                        + "WHERE d.fulfillment_status IS NOT NULL AND s.id IS NULL", Long.class)).isZero();
        assertThat(linesOfSale(saleId)).isEqualTo(1);
    }

    private Map<String, Object> row(UUID lineId) {
        return jdbc.queryForMap(
                "SELECT id, sale_id, product_id, quantity, price, sub_total, invoice_description, "
                        + "created_at, fulfillment_type, fulfillment_status, item_id, work_result_id, "
                        + "fulfillment_transformation_id FROM sale_detail WHERE id = ?", lineId);
    }

    private long linesOfSale(UUID saleId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sale_detail WHERE sale_id = ?", Long.class, saleId);
    }

    private long linesWithItem(UUID itemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sale_detail WHERE item_id = ?", Long.class, itemId);
    }

    private void assertReadiness(
            Shop shop, UUID saleId, SaleFulfillmentStatus expected, int fulfilled) {
        as(shop.seller());
        SaleResponseDTO sale = saleService.get(saleId);
        assertThat(sale.fulfillmentStatus()).isEqualTo(expected);
        assertThat(sale.details())
                .filteredOn(detail -> detail.fulfillment().status() == FulfillmentStatus.FULFILLED)
                .hasSize(fulfilled);
        if (expected != SaleFulfillmentStatus.READY_TO_COMPLETE) {
            assertThatThrownBy(() -> completeAsCf(saleId))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("no ha cumplido");
            assertThat(saleService.get(saleId).status()).isEqualTo(SaleStatus.PENDING);
        }
    }

    // ── 57. Lineas nuevas despues de enviar ──────────────────────

    @Test
    void addingAfterDispatchOnlySendsTheNewRequest() {
        Shop shop = shop();
        Product first = leaf(shop, 110, 2);
        stocked(shop, first, 1);
        Product installation = job(shop, "AJUSTE");

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Elena");
        sale = saleService.addDetail(sale.id(), specialRequest(first, 1, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        SaleDetailResponseDTO firstLine = onlyLine(sale);

        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("80.00"), null, null));
        SaleDetailResponseDTO added = sale.details().stream()
                .filter(detail -> !detail.id().equals(firstLine.id())).findFirst().orElseThrow();
        assertThat(added.fulfillment().status()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(sale.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.IN_PROGRESS);

        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        SaleDetailResponseDTO firstAgain = lineById(sale, firstLine.id());
        assertThat(firstAgain.fulfillment().dispatchedAt()).isEqualTo(firstLine.fulfillment().dispatchedAt());
        assertThat(firstAgain.item().id()).isEqualTo(firstLine.item().id());
        assertThat(lineById(sale, added.id()).fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);

        // Un tercer envio sin nada nuevo no cambia nada.
        SaleResponseDTO again = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        assertThat(again.details()).extracting(detail -> detail.fulfillment().dispatchedAt())
                .containsExactlyInAnyOrderElementsOf(
                        sale.details().stream().map(detail -> detail.fulfillment().dispatchedAt()).toList());
        as(shop.control());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null)).hasSize(1);
        as(shop.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null)).hasSize(1);
    }

    // ── 44. Nombre de referencia ─────────────────────────────────

    @Test
    void aNamelessSaleCannotBeSentToTheWorkshopUntilItHasAName() {
        Shop shop = shop();
        Product installation = job(shop, "REVISION");

        as(shop.seller());
        SaleResponseDTO sale = saleService.create(
                new CreateSaleRequestDTO(null, PLATE, TaxMode.UNRESOLVED, null));
        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("50.00"), null, null));
        UUID saleId = sale.id();

        assertThatThrownBy(() -> saleService.dispatch(saleId, new DispatchSaleRequestDTO(null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("nombre");

        sale = saleService.dispatch(saleId, new DispatchSaleRequestDTO("Taller Norte"));
        assertThat(sale.customerName()).isEqualToIgnoringCase("Taller Norte");
        assertThat(sale.savedForLater()).isTrue();
        assertThat(sale.taxMode()).isEqualTo(TaxMode.UNRESOLVED);
    }

    // ── 30. Concurrencia de Control ──────────────────────────────

    @Test
    void twoSalesRaceForTheLastUnitAndExactlyOneGetsIt() throws Exception {
        Shop shop = shop();
        Product leaf = leaf(shop, 140, 2);
        UUID unit = stocked(shop, leaf, 1).get(0).getId();

        as(shop.seller());
        UUID first = openUnresolvedSale("Uno").id();
        UUID second = openUnresolvedSale("Dos").id();

        // Los dos vendedores vieron una unidad libre y confirman a la vez: la
        // reserva se decide bajo el cerrojo del inventario, no con lo que vieron.
        List<Boolean> outcomes = concurrently(shop.seller(),
                () -> saleService.addDetail(first, specialRequest(leaf, 1, null)),
                () -> saleService.addDetail(second, specialRequest(leaf, 1, null)));
        assertThat(outcomes).containsOnly(true);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_detail WHERE item_id = ?", Long.class, unit)).isEqualTo(1L);

        as(shop.seller());
        List<Boolean> dispatched = concurrently(shop.seller(),
                () -> saleService.dispatch(first, new DispatchSaleRequestDTO(null)),
                () -> saleService.dispatch(second, new DispatchSaleRequestDTO(null)));
        assertThat(dispatched).containsOnly(true);

        as(shop.seller());
        List<FulfillmentType> routes = List.of(
                onlyLine(saleService.get(first)).fulfillment().type(),
                onlyLine(saleService.get(second)).fulfillment().type());
        assertThat(routes).containsExactlyInAnyOrder(
                FulfillmentType.STOCK_RELEASE, FulfillmentType.TRANSFORMATION);
        assertThat(stockOf(leaf, shop)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE i.product_id = ? AND item.status = 'SOLD'", Long.class, leaf.getId()))
                .isEqualTo(1L);
    }

    @Test
    void twoControlUsersReleasingTheSameRequestOnlyDeliverOnce() throws Exception {
        Shop shop = shop();
        Product leaf = leaf(shop, 160, 2);
        List<InventoryItem> units = stocked(shop, leaf, 3);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(
                openUnresolvedSale("Doble").id(), specialRequest(leaf, 1, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID line = onlyLine(sale).id();
        UUID reserved = onlyLine(sale).item().id();
        List<UUID> others = units.stream().map(InventoryItem::getId)
                .filter(id -> !id.equals(reserved)).toList();

        List<Boolean> outcomes = concurrently(shop.control(),
                () -> fulfillmentService.release(line, new ReleaseFulfillmentRequestDTO(others.get(0))),
                () -> fulfillmentService.release(line, new ReleaseFulfillmentRequestDTO(others.get(1))));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);

        // Exactamente una unidad salio, y es la que quedo en la venta.
        long sold = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE i.product_id = ? AND item.status = 'SOLD'", Long.class, leaf.getId());
        assertThat(sold).isEqualTo(1L);
        as(shop.seller());
        UUID delivered = onlyLine(saleService.get(sale.id())).item().id();
        assertThat(itemStatus(delivered)).isEqualTo("SOLD");
        assertThat(stockOf(leaf, shop)).isEqualTo(2);
    }

    @Test
    void controlCannotDeliverAUnitReservedForAnotherSale() {
        Shop shop = shop();
        Product leaf = leaf(shop, 170, 2);
        stocked(shop, leaf, 2);

        as(shop.seller());
        SaleResponseDTO a = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("A").id(), specialRequest(leaf, 1, null)).id(), new DispatchSaleRequestDTO(null));
        SaleResponseDTO b = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("B").id(), specialRequest(leaf, 1, null)).id(), new DispatchSaleRequestDTO(null));
        UUID reservedForB = onlyLine(b).item().id();

        as(shop.control());
        assertThatThrownBy(() -> fulfillmentService.release(
                onlyLine(a).id(), new ReleaseFulfillmentRequestDTO(reservedForB)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no esta disponible");
    }

    // ── 31. Concurrencia del Operador ────────────────────────────

    @Test
    void anotherOperatorCannotFinishARequestThatIsAlreadyTaken() {
        Shop shop = shop();
        Product installation = job(shop, "SOLDADURA");

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Toma").id(),
                new AddSaleDetailRequestDTO(installation.getId(), null, null, 1,
                        new BigDecimal("90.00"), null, null));
        UUID line = onlyLine(saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null))).id();

        as(shop.operator());
        fulfillmentService.start(line);
        as(shop.operator2());
        assertThatThrownBy(() -> fulfillmentService.completeService(line))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya la tomo");

        as(shop.operator());
        fulfillmentService.completeService(line);
        assertThatThrownBy(() -> fulfillmentService.completeService(line))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ya fue cumplida");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_log l JOIN job j ON j.id = l.job_id WHERE j.product_id = ?",
                Long.class, installation.getId())).isEqualTo(1L);
    }

    @Test
    void theSameSourceCannotBeUsedForTwoRequests() {
        Shop shop = shop();
        Product target = leaf(shop, 70, 2);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 200, 2), 1).get(0);

        as(shop.seller());
        UUID first = onlyLine(saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("X").id(), specialRequest(target, 1, null)).id(),
                new DispatchSaleRequestDTO(null))).id();
        UUID second = onlyLine(saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Y").id(), specialRequest(target, 1, null)).id(),
                new DispatchSaleRequestDTO(null))).id();

        as(shop.operator());
        fulfillmentService.transform(first, new FulfillTransformationRequestDTO(source.getId(), null));
        assertThatThrownBy(() -> fulfillmentService.transform(
                second, new FulfillTransformationRequestDTO(source.getId(), null)))
                .isInstanceOf(ValidationException.class);
        assertThat(fulfillmentService.get(second).status()).isEqualTo(FulfillmentStatus.QUEUED);
    }

    // ── 32. Sin materia prima ────────────────────────────────────

    @Test
    void aBlockedRequestKeepsTheSaleOpenUntilItIsResumedAndFinished() {
        Shop shop = shop();
        Product target = leaf(shop, 60, 2);
        emptyInventory(shop, target);

        as(shop.seller());
        SaleResponseDTO sale = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Bloq").id(), specialRequest(target, 1, null)).id(),
                new DispatchSaleRequestDTO(null));
        UUID line = onlyLine(sale).id();

        as(shop.operator());
        assertThat(fulfillmentService.getSources(line, null)).isEmpty();
        FulfillmentTaskResponseDTO task = fulfillmentService.block(
                line, new BlockFulfillmentRequestDTO("Sin materia prima"));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.BLOCKED);
        assertThat(task.note()).isEqualTo("Sin materia prima");

        as(shop.seller());
        SaleResponseDTO blocked = saleService.get(sale.id());
        assertThat(onlyLine(blocked).fulfillment().note()).isEqualTo("Sin materia prima");
        assertThat(blocked.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> completeAsCf(sale.id())).isInstanceOf(ValidationException.class);

        // Llega material: se retoma y se termina.
        InventoryItem source = stocked(shop, leaf(shop, 100, 2), 1).get(0);
        as(shop.operator());
        assertThat(fulfillmentService.getSources(line, null)).extracting(source2 -> source2.item().id())
                .contains(source.getId());
        assertThat(fulfillmentService.start(line).status()).isEqualTo(FulfillmentStatus.IN_PROGRESS);
        assertThat(fulfillmentService.transform(line,
                new FulfillTransformationRequestDTO(source.getId(), null)).status())
                .isEqualTo(FulfillmentStatus.FULFILLED);
    }

    // ── 33-35. Anular ────────────────────────────────────────────

    @Test
    void cancellingBeforeReleaseFreesTheReservedUnit() {
        Shop shop = shop();
        Product leaf = leaf(shop, 125, 2);
        stocked(shop, leaf, 1);
        Product installation = job(shop, "LIMPIEZA");

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Anula").id(),
                specialRequest(leaf, 1, null));
        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("40.00"), null, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID reserved = lineByType(sale, FulfillmentType.STOCK_RELEASE).item().id();
        assertThat(stockOf(leaf, shop)).isZero();

        SaleResponseDTO cancelled = saleService.cancel(sale.id(), new CancelSaleRequestDTO("Cliente desistio"));
        assertThat(cancelled.status()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(cancelled.details()).extracting(detail -> detail.fulfillment().status())
                .containsOnly(FulfillmentStatus.CANCELLED);
        assertThat(itemStatus(reserved)).isEqualTo("AVAILABLE");
        assertThat(stockOf(leaf, shop)).isEqualTo(1);

        // Nada queda en las colas.
        as(shop.control());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null)).isEmpty();
        as(shop.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null)).isEmpty();
    }

    @Test
    void workInProgressBlocksCancellation() {
        Shop shop = shop();
        Product installation = job(shop, "PINTURA");

        as(shop.seller());
        UUID saleId = saleService.addDetail(openUnresolvedSale("Curso").id(),
                new AddSaleDetailRequestDTO(installation.getId(), null, null, 1,
                        new BigDecimal("70.00"), null, null)).id();
        UUID line = onlyLine(saleService.dispatch(saleId, new DispatchSaleRequestDTO(null))).id();

        as(shop.operator());
        fulfillmentService.start(line);

        as(shop.seller());
        assertThatThrownBy(() -> saleService.cancel(saleId, new CancelSaleRequestDTO("Ya no")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("trabajando");
        assertThatThrownBy(() -> saleService.removeDetail(line))
                .isInstanceOf(ValidationException.class);
        assertThat(saleService.get(saleId).status()).isEqualTo(SaleStatus.PENDING);
    }

    @Test
    void cancellingAfterFabricationKeepsThePieceAndTheHistory() {
        Shop shop = shop();
        Product target = leaf(shop, 80, 2);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 200, 2), 1).get(0);

        as(shop.seller());
        UUID saleId = saleService.dispatch(saleService.addDetail(openUnresolvedSale("Hecho").id(),
                specialRequest(target, 1, null)).id(), new DispatchSaleRequestDTO(null)).id();
        UUID line = onlyLine(saleService.get(saleId)).id();

        as(shop.operator());
        FulfillmentTaskResponseDTO task = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), null));
        UUID output = task.itemId();

        as(shop.seller());
        // Una solicitud ya cumplida no se quita sola: se anula la venta.
        assertThatThrownBy(() -> saleService.removeDetail(line))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("anule la venta");
        SaleResponseDTO cancelled = saleService.cancel(saleId, new CancelSaleRequestDTO("No paso a recoger"));
        assertThat(cancelled.status()).isEqualTo(SaleStatus.CANCELLED);
        // La pieza fabricada es real: vuelve al stock con su identidad.
        assertThat(itemStatus(output)).isEqualTo("AVAILABLE");
        assertThat(stockOf(target, shop)).isEqualTo(1);
        // La transformacion y su trabajo siguen existiendo; el trabajo queda libre.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transformation WHERE id = ?",
                Long.class, task.transformationId())).isEqualTo(1L);
        assertThat(workResultStatusOfItem(output)).isEqualTo("PENDING");
        assertThat(onlyLine(cancelled).fulfillment().status()).isEqualTo(FulfillmentStatus.FULFILLED);
        // La historia tecnica no se reescribe: la transformacion sigue con su
        // entrada consumida y su salida, la linea anulada conserva el enlace a
        // ella, y el trabajo de la salida existe —PENDING significa "hecho y no
        // facturado", que es exactamente lo que es ahora—.
        assertThat(onlyLine(cancelled).fulfillment().transformationId()).isEqualTo(task.transformationId());
        assertThat(itemStatus(source.getId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForList(
                "SELECT role || ':' || item_id FROM transformation_item WHERE transformation_id = ?",
                String.class, task.transformationId()))
                .contains("INPUT:" + source.getId(), "OUTPUT:" + output);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_result WHERE item_id = ?",
                Long.class, output)).isEqualTo(1L);
        assertThat(productOfItem(output)).isEqualTo(target.getId());
    }

    /**
     * {@code WorkResult.PENDING} es "trabajo hecho y no facturado", no "trabajo
     * por hacer". Anular la venta que lo tenia no lo deshace ni lo vuelve a
     * pedir: lo suelta (ninguna linea lo apunta) y queda disponible para
     * facturarse UNA vez mas, junto con la pieza real que lo materializa.
     */
    @Test
    void aCancelledFabricationStaysDoneWorkAndCanBeBilledOnlyOnceMore() throws Exception {
        Shop shop = shop();
        Product target = leaf(shop, 80, 2);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 200, 2), 1).get(0);

        // 1-4. Venta, envio, fabricacion: el trabajo nace hecho y ligado a la linea.
        as(shop.seller());
        UUID firstSale = saleService.dispatch(saleService.addDetail(openUnresolvedSale("Primero").id(),
                specialRequest(target, 1, null)).id(), new DispatchSaleRequestDTO(null)).id();
        UUID firstLine = onlyLine(saleService.get(firstSale)).id();
        as(shop.operator());
        FulfillmentTaskResponseDTO made = fulfillmentService.transform(
                firstLine, new FulfillTransformationRequestDTO(source.getId(), null));
        UUID output = made.itemId();
        UUID workResult = jdbc.queryForObject(
                "SELECT id FROM work_result WHERE item_id = ?", UUID.class, output);
        assertThat(workResultStatusOfItem(output)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT work_result_id FROM sale_detail WHERE id = ?",
                UUID.class, firstLine)).isEqualTo(workResult);

        // 5. Se anula la venta.
        as(shop.seller());
        saleService.cancel(firstSale, new CancelSaleRequestDTO("No paso a recoger"));

        // 6-7. La historia tecnica no se toca.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transformation WHERE id = ?",
                Long.class, made.transformationId())).isEqualTo(1L);
        assertThat(itemStatus(source.getId())).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForList(
                "SELECT role || ':' || item_id FROM transformation_item WHERE transformation_id = ?",
                String.class, made.transformationId()))
                .contains("INPUT:" + source.getId(), "OUTPUT:" + output);
        assertThat(onlyLine(saleService.get(firstSale)).fulfillment().status())
                .isEqualTo(FulfillmentStatus.FULFILLED);
        // 8. La pieza es real y vuelve al stock.
        assertThat(itemStatus(output)).isEqualTo("AVAILABLE");
        assertThat(stockOf(target, shop)).isEqualTo(1);
        // 9. El trabajo sigue existiendo, con su pieza: es trabajo hecho.
        assertThat(jdbc.queryForObject("SELECT item_id FROM work_result WHERE id = ?",
                UUID.class, workResult)).isEqualTo(output);
        assertThat(workResultStatusOfItem(output)).isEqualTo("PENDING");
        // 10. Suelto: ninguna linea lo apunta y se ofrece como disponible.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_detail WHERE work_result_id = ?",
                Long.class, workResult)).isZero();
        assertThat(workResultService.getAll(new SearchWorkResultRequestDTO(WorkResultStatus.PENDING)))
                .extracting(WorkResultResponseDTO::id)
                .contains(workResult);
        // La puerta antigua —elegir el trabajo o la unidad desde la caja— sigue cerrada.
        UUID direct = openUnresolvedSale("Directo").id();
        assertThatThrownBy(() -> saleService.addDetail(direct, new AddSaleDetailRequestDTO(
                target.getId(), output, workResult, null, PRICE, null, null)))
                .isInstanceOf(ValidationException.class);

        // 11. Dos clientes la piden a la vez: solo uno se queda la pieza y su trabajo.
        UUID saleA = saleService.addDetail(openUnresolvedSale("Cliente A").id(),
                specialRequest(target, 1, null)).id();
        UUID saleB = saleService.addDetail(openUnresolvedSale("Cliente B").id(),
                specialRequest(target, 1, null)).id();
        assertThat(concurrently(shop.seller(),
                () -> saleService.dispatch(saleA, new DispatchSaleRequestDTO(null)),
                () -> saleService.dispatch(saleB, new DispatchSaleRequestDTO(null))))
                .containsExactly(true, true);
        as(shop.seller());
        List<SaleDetailResponseDTO> lines = List.of(
                onlyLine(saleService.get(saleA)), onlyLine(saleService.get(saleB)));
        assertThat(lines).extracting(line -> line.fulfillment().type())
                .containsExactlyInAnyOrder(FulfillmentType.STOCK_RELEASE, FulfillmentType.TRANSFORMATION);
        SaleDetailResponseDTO winner = lines.stream()
                .filter(line -> line.fulfillment().type() == FulfillmentType.STOCK_RELEASE)
                .findFirst().orElseThrow();
        UUID winnerSale = saleIdOf(winner.id());
        assertThat(winner.item().id()).isEqualTo(output);

        as(shop.control());
        fulfillmentService.release(winner.id(), new ReleaseFulfillmentRequestDTO(null));
        assertThat(jdbc.queryForList("SELECT id FROM sale_detail WHERE work_result_id = ?",
                UUID.class, workResult)).containsExactly(winner.id());

        // Completar dos veces a la vez la factura: el trabajo se vende una sola vez.
        assertThat(concurrently(shop.seller(),
                () -> completeAsCf(winnerSale),
                () -> completeAsCf(winnerSale)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(workResultStatusOfItem(output)).isEqualTo("SOLD");
        assertThat(itemStatus(output)).isEqualTo("SOLD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_detail WHERE work_result_id = ?",
                Long.class, workResult)).isEqualTo(1L);
        as(shop.seller());
        assertThat(workResultService.getAll(new SearchWorkResultRequestDTO(WorkResultStatus.PENDING)))
                .extracting(WorkResultResponseDTO::id)
                .doesNotContain(workResult);
    }

    @Test
    void aPendingLineCanBeRemovedAndItsReservedUnitReturnsToStock() {
        Shop shop = shop();
        Product leaf = leaf(shop, 115, 2);
        UUID unit = stocked(shop, leaf, 1).get(0).getId();

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Quita").id(),
                specialRequest(leaf, 1, null));
        assertThat(onlyLine(sale).item().id()).isEqualTo(unit);
        assertThat(stockOf(leaf, shop)).isZero();
        assertThat(lotRemaining(leaf, shop)).isZero();

        sale = saleService.removeDetail(onlyLine(sale).id());
        assertThat(sale.details()).isEmpty();
        assertThat(itemStatus(unit)).isEqualTo("AVAILABLE");
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        // Y vuelve al lote del que salio.
        assertThat(lotRemaining(leaf, shop)).isEqualTo(1);
    }

    // ── 48. Aislamiento por taller ───────────────────────────────

    @Test
    void aNeighbourWorkshopCannotSeeOrFulfilAnotherWorkshopsRequests() {
        Shop shop = shop();
        Shop neighbour = shop();
        Product leaf = leaf(shop, 145, 2);
        stocked(shop, leaf, 1);

        as(shop.seller());
        SaleResponseDTO sale = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Propio").id(), specialRequest(leaf, 1, null)).id(),
                new DispatchSaleRequestDTO(null));
        UUID line = onlyLine(sale).id();

        as(neighbour.control());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null)).isEmpty();
        assertThatThrownBy(() -> fulfillmentService.get(line))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> fulfillmentService.release(line, new ReleaseFulfillmentRequestDTO(null)))
                .isInstanceOf(EntityNotFoundException.class);
        as(neighbour.seller());
        assertThatThrownBy(() -> saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(saleService.getReady(null)).isEmpty();
    }

    // ── Reserva al confirmar la linea ────────────────────────────

    /**
     * Pedir diez con seis terminadas: las seis quedan reservadas al agregar
     * (Control) y cuatro se fabrican (Operador). La cuenta lleva las diez. Otra
     * venta ya no ve libre ninguna de las seis, y enviar no reserva otra vez.
     */
    @Test
    void aSpecialRequestedAboveStockReservesWhatThereIsAndFabricatesTheRest() {
        Shop shop = shop();
        Product leaf = leaf(shop, 112, 2);
        List<UUID> units = stocked(shop, leaf, 6).stream().map(InventoryItem::getId).toList();

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Diez").id();
        SaleResponseDTO sale = saleService.addDetail(saleId, specialRequest(leaf, 10, null));

        assertThat(sale.details()).hasSize(10);
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(10)));
        List<SaleDetailResponseDTO> fromStock = sale.details().stream()
                .filter(detail -> detail.fulfillment().type() == FulfillmentType.STOCK_RELEASE).toList();
        List<SaleDetailResponseDTO> toFabricate = sale.details().stream()
                .filter(detail -> detail.fulfillment().type() == FulfillmentType.TRANSFORMATION).toList();
        assertThat(fromStock).hasSize(6)
                .allSatisfy(detail -> assertThat(detail.fulfillment().status()).isEqualTo(FulfillmentStatus.PENDING));
        assertThat(fromStock).extracting(detail -> detail.item().id()).containsExactlyInAnyOrderElementsOf(units);
        assertThat(toFabricate).hasSize(4).allSatisfy(detail -> assertThat(detail.item()).isNull());
        assertThat(units).allSatisfy(unit -> assertThat(itemStatus(unit)).isEqualTo("SOLD"));
        assertThat(stockOf(leaf, shop)).isZero();
        assertThat(lotRemaining(leaf, shop)).isZero();

        // Otra venta pide la misma pieza: no hay unidad terminada que prometerle.
        SaleResponseDTO other = saleService.addDetail(openUnresolvedSale("Otra").id(), specialRequest(leaf, 3, null));
        assertThat(other.details()).allSatisfy(detail -> {
            assertThat(detail.fulfillment().type()).isEqualTo(FulfillmentType.TRANSFORMATION);
            assertThat(detail.item()).isNull();
        });
        assertNoUnitIsOwnedTwice();

        // Enviar: las mismas unidades, ningun descuento mas.
        sale = saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        assertThat(sale.details()).extracting(detail -> detail.fulfillment().status())
                .containsOnly(FulfillmentStatus.QUEUED);
        assertThat(sale.details().stream()
                .filter(detail -> detail.fulfillment().type() == FulfillmentType.STOCK_RELEASE)
                .map(detail -> detail.item().id()))
                .containsExactlyInAnyOrderElementsOf(units);
        assertThat(stockOf(leaf, shop)).isZero();
        assertThat(lotRemaining(leaf, shop)).isZero();
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(10)));

        as(shop.control());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null)).hasSize(6);
        as(shop.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null)).hasSize(4);
    }

    /** Sin piezas terminadas, todo lo pedido es para fabricar. Eso no es sobreventa. */
    @Test
    void aSpecialWithoutFinishedStockGoesEntirelyToFabrication() {
        Shop shop = shop();
        Product emptied = leaf(shop, 113, 2);
        emptyInventory(shop, emptied);
        Product neverStocked = leaf(shop, 114, 2);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Sin stock").id();
        saleService.addDetail(saleId, specialRequest(emptied, 10, null));
        SaleResponseDTO sale = saleService.addDetail(saleId, specialRequest(neverStocked, 2, null));

        assertThat(sale.details()).hasSize(12).allSatisfy(detail -> {
            assertThat(detail.fulfillment().type()).isEqualTo(FulfillmentType.TRANSFORMATION);
            assertThat(detail.fulfillment().status()).isEqualTo(FulfillmentStatus.PENDING);
            assertThat(detail.item()).isNull();
        });
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(12)));
        assertThat(stockOf(emptied, shop)).isZero();
    }

    /**
     * La existencia se descuenta UNA vez, al confirmar la linea: ni enviar, ni
     * que Control entregue (aunque cambie la unidad), ni emitir la venta la
     * vuelven a tocar.
     */
    @Test
    void stockMovesOnceFromConfirmationToInvoice() {
        Shop shop = shop();
        Product leaf = leaf(shop, 116, 2);
        List<UUID> units = stocked(shop, leaf, 3).stream().map(InventoryItem::getId).toList();
        Map<String, Integer> stock = new java.util.LinkedHashMap<>();
        stock.put("before", stockOf(leaf, shop));

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Una vez").id();
        UUID line = onlyLine(saleService.addDetail(saleId, specialRequest(leaf, 1, null))).id();
        UUID reserved = onlyLine(saleService.get(saleId)).item().id();
        stock.put("reserved", stockOf(leaf, shop));
        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        stock.put("dispatched", stockOf(leaf, shop));

        as(shop.control());
        UUID other = units.stream().filter(unit -> !unit.equals(reserved)).findFirst().orElseThrow();
        fulfillmentService.release(line, new ReleaseFulfillmentRequestDTO(other));
        stock.put("released", stockOf(leaf, shop));
        assertThat(itemStatus(other)).isEqualTo("SOLD");
        assertThat(itemStatus(reserved)).isEqualTo("AVAILABLE");

        as(shop.seller());
        completeAsCf(saleId);
        stock.put("completed", stockOf(leaf, shop));

        assertThat(stock).containsExactly(
                Map.entry("before", 3), Map.entry("reserved", 2), Map.entry("dispatched", 2),
                Map.entry("released", 2), Map.entry("completed", 2));
        assertThat(lotRemaining(leaf, shop)).isEqualTo(2);
        assertThat(itemStatus(other)).isEqualTo("SOLD");
        assertNoUnitIsOwnedTwice();
    }

    /**
     * Bajar la cantidad es quitar lineas: cada una suelta su unidad en el acto.
     * Subirla es agregar mas, que vuelve a mirar el stock. Anular suelta lo que
     * quede, y las unidades se pueden reservar otra vez.
     */
    @Test
    void reservedUnitsAreReleasedByRemovingLinesAndByCancelling() {
        Shop shop = shop();
        Product leaf = leaf(shop, 117, 2);
        stocked(shop, leaf, 5);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Suelta").id();
        SaleResponseDTO sale = saleService.addDetail(saleId, specialRequest(leaf, 5, null));
        assertThat(stockOf(leaf, shop)).isZero();

        saleService.removeDetail(sale.details().get(0).id());
        sale = saleService.removeDetail(sale.details().get(1).id());
        assertThat(sale.details()).hasSize(3);
        assertThat(stockOf(leaf, shop)).isEqualTo(2);
        assertThat(lotRemaining(leaf, shop)).isEqualTo(2);
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));

        // Subir: una mas, que hay; despues dos mas con una sola libre.
        sale = saleService.addDetail(saleId, specialRequest(leaf, 1, null));
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        sale = saleService.addDetail(saleId, specialRequest(leaf, 2, null));
        assertThat(sale.details()).hasSize(6);
        assertThat(sale.details()).filteredOn(detail -> detail.item() != null).hasSize(5);
        assertThat(stockOf(leaf, shop)).isZero();

        saleService.cancel(saleId, new CancelSaleRequestDTO("Cliente desistio"));
        assertThat(stockOf(leaf, shop)).isEqualTo(5);
        assertThat(lotRemaining(leaf, shop)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE i.product_id = ? AND item.status = 'AVAILABLE'", Long.class, leaf.getId()))
                .isEqualTo(5L);

        // Libres de verdad: otra venta las reserva todas.
        SaleResponseDTO again = saleService.addDetail(openUnresolvedSale("Otra vez").id(), specialRequest(leaf, 5, null));
        assertThat(again.details()).allSatisfy(detail -> assertThat(detail.item()).isNotNull());
        assertThat(stockOf(leaf, shop)).isZero();
        assertNoUnitIsOwnedTwice();
    }

    /**
     * Lo que no se fabrica no se puede prometer de mas: con diez, una venta
     * toma ocho, la segunda no puede pedir tres pero si dos, y una tercera ya
     * no puede pedir ninguna. Anular la primera devuelve sus ocho.
     */
    @Test
    void aStockOnlyProductCannotBePromisedBeyondWhatIsLeft() {
        Shop shop = shop();
        Product screw = normalProduct(shop, "TORNILLO");
        stocked(shop, screw, 10);

        as(shop.seller());
        UUID first = openSaleWithoutPlate("A").id();
        UUID second = openSaleWithoutPlate("B").id();
        UUID third = openSaleWithoutPlate("C").id();

        saleService.addDetail(first, normalRequest(screw, 8));
        assertThat(stockOf(screw, shop)).isEqualTo(2);
        assertThatThrownBy(() -> saleService.addDetail(second, normalRequest(screw, 3)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo hay 2 unidades disponibles");
        assertThat(saleService.get(second).details()).isEmpty();
        saleService.addDetail(second, normalRequest(screw, 2));
        assertThat(stockOf(screw, shop)).isZero();
        assertThatThrownBy(() -> saleService.addDetail(third, normalRequest(screw, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No hay unidades disponibles de este producto");

        saleService.cancel(first, new CancelSaleRequestDTO("Cliente desistio"));
        assertThat(stockOf(screw, shop)).isEqualTo(8);
        saleService.addDetail(third, normalRequest(screw, 1));
        assertThat(stockOf(screw, shop)).isEqualTo(7);
    }

    /** Dos cajas confirman a la vez la ultima pieza normal: una la lleva, la otra se entera. */
    @Test
    void twoSalesRaceForTheLastStockOnlyUnitAndExactlyOneGetsIt() throws Exception {
        Shop shop = shop();
        Product screw = normalProduct(shop, "ARANDELA");
        stocked(shop, screw, 1);

        as(shop.seller());
        UUID first = openSaleWithoutPlate("Uno").id();
        UUID second = openSaleWithoutPlate("Dos").id();
        List<Boolean> outcomes = concurrently(shop.seller(),
                () -> saleService.addDetail(first, normalRequest(screw, 1)),
                () -> saleService.addDetail(second, normalRequest(screw, 1)));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(stockOf(screw, shop)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_detail WHERE product_id = ?", Long.class, screw.getId()))
                .isEqualTo(1L);
    }

    /**
     * Una pieza pedida antes de reservar al agregar llego a la base sin ruta:
     * enviar la decide como antes, con el stock de ese momento.
     */
    @Test
    void aRequestAddedBeforeReservingOnAddIsStillRoutedWhenSent() {
        Shop shop = shop();
        Product leaf = leaf(shop, 119, 2);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Anterior").id();
        UUID line = onlyLine(saleService.addDetail(saleId, specialRequest(leaf, 1, null))).id();
        // Como quedaron las lineas pedidas antes de este cambio.
        jdbc.update("UPDATE sale_detail SET fulfillment_type = NULL WHERE id = ?", line);
        UUID unit = stocked(shop, leaf, 1).get(0).getId();

        SaleDetailResponseDTO sent = onlyLine(saleService.dispatch(saleId, new DispatchSaleRequestDTO(null)));
        assertThat(sent.fulfillment().type()).isEqualTo(FulfillmentType.STOCK_RELEASE);
        assertThat(sent.item().id()).isEqualTo(unit);
        assertThat(stockOf(leaf, shop)).isZero();
    }

    /**
     * Guardar para despues es quedarse con lo apartado A PROPOSITO: la venta
     * aparece en sus pendientes, reabrirla (leerla) o listarla no toca el
     * inventario, enviarla tampoco reserva otra vez, y solo anularla lo suelta,
     * una vez. Las mismas unidades quedan libres para otra venta.
     */
    @Test
    void aSavedSaleKeepsItsReservationsAndReopensWithoutReservingAgain() {
        Shop shop = shop();
        Product leaf = leaf(shop, 122, 2);
        stocked(shop, leaf, 3);
        Product screw = normalProduct(shop, "PERNO");
        stocked(shop, screw, 4);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Guardada").id();
        saleService.addDetail(saleId, specialRequest(leaf, 2, null));
        saleService.addDetail(saleId, normalRequest(screw, 3));
        Map<UUID, UUID> held = unitsByLine(saleService.get(saleId));
        assertThat(held).hasSize(2);
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(stockOf(screw, shop)).isEqualTo(1);

        SaleResponseDTO saved = saleService.saveForLater(saleId, new SaveForLaterRequestDTO("Guardada"));
        assertThat(saved.savedForLater()).isTrue();
        assertThat(saved.status()).isEqualTo(SaleStatus.PENDING);
        assertThat(saleService.getAll(new SearchSaleRequestDTO(
                null, null, null, null, null, null, null, null, null, SaleStatus.PENDING, null, null, true)))
                .extracting(SaleResponseDTO::id).contains(saleId);

        // Reabrirla es leerla: mismas lineas, mismas unidades, nada reservado de nuevo.
        SaleResponseDTO reopened = saleService.get(saleId);
        assertThat(unitsByLine(reopened)).isEqualTo(held);
        assertThat(reopened.details()).hasSize(3);
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(stockOf(screw, shop)).isEqualTo(1);
        assertThat(lotRemaining(leaf, shop)).isEqualTo(1);
        assertThat(held.values()).allSatisfy(unit -> assertThat(itemStatus(unit)).isEqualTo("SOLD"));

        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        assertThat(unitsByLine(saleService.get(saleId))).isEqualTo(held);
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertNoUnitIsOwnedTwice();

        saleService.cancel(saleId, new CancelSaleRequestDTO("Cliente desistio"));
        assertThat(stockOf(leaf, shop)).isEqualTo(3);
        assertThat(stockOf(screw, shop)).isEqualTo(4);
        assertThat(lotRemaining(leaf, shop)).isEqualTo(3);
        assertThat(lotRemaining(screw, shop)).isEqualTo(4);
        assertThat(held.values()).allSatisfy(unit -> assertThat(itemStatus(unit)).isEqualTo("AVAILABLE"));

        // Las MISMAS unidades, libres de verdad: otra venta las toma.
        SaleResponseDTO other = saleService.addDetail(openUnresolvedSale("Otra").id(), specialRequest(leaf, 3, null));
        assertThat(other.details()).extracting(detail -> detail.item().id())
                .containsAll(held.values());
        assertNoUnitIsOwnedTwice();
    }

    /**
     * Apartar no es vender. Una venta abierta con unidades reservadas baja el
     * stock libre, pero no cuenta en el cierre de caja, ni en lo vendido hoy,
     * ni en los productos mas vendidos: eso cambia solo al emitirla.
     */
    @Test
    void anOpenSaleHoldingStockIsNotRevenueUntilItIsCompleted() {
        Shop shop = shop();
        Product leaf = leaf(shop, 123, 2);
        stocked(shop, leaf, 2);
        Product screw = normalProduct(shop, "TUERCA");
        stocked(shop, screw, 5);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Apartado").id();
        UUID line = onlyLine(saleService.addDetail(saleId, specialRequest(leaf, 1, null))).id();
        SaleResponseDTO open = saleService.addDetail(saleId, normalRequest(screw, 2));
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(stockOf(screw, shop)).isEqualTo(3);
        assertThat(open.status()).isEqualTo(SaleStatus.PENDING);
        assertThat(open.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(open.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));

        assertNoRevenueYet();

        // Enviada y entregada por Control sigue sin ser ingreso.
        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        as(shop.control());
        fulfillmentService.release(line, new ReleaseFulfillmentRequestDTO(null));
        as(shop.seller());
        assertNoRevenueYet();

        // Emitirla es la frontera financiera; el inventario no se mueve otra vez.
        completeAsCf(saleId);
        BigDecimal billed = PRICE.multiply(BigDecimal.valueOf(3));
        CashClosureResponseDTO cash = cashClosureService.getPreview();
        assertThat(cash.itemsBilledAmount()).isEqualByComparingTo(billed);
        assertThat(cash.invoicing().invoiceCount()).isEqualTo(1);
        SaleDashboardStatsDTO stats = saleDashboardService.getDashboard(1).general();
        assertThat(stats.kpis().completedSalesToday()).isEqualTo(1);
        assertThat(stats.kpis().amountSoldToday()).isEqualByComparingTo(billed);
        assertThat(stats.kpis().pendingSales()).isZero();
        assertThat(stats.topProductsToday()).extracting(TopProductDTO::productId)
                .containsExactlyInAnyOrder(leaf.getId(), screw.getId());
        assertThat(adminDashboardService.getOverview().topSellingProducts())
                .extracting(ProductPerformanceDTO::productId)
                .containsExactlyInAnyOrder(leaf.getId(), screw.getId());
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(stockOf(screw, shop)).isEqualTo(3);
    }

    private void assertNoRevenueYet() {
        CashClosureResponseDTO cash = cashClosureService.getPreview();
        assertThat(cash.itemsBilledAmount()).isEqualByComparingTo("0");
        assertThat(cash.items()).isEmpty();
        assertThat(cash.invoicing().invoiceCount()).isZero();
        assertThat(cash.invoicing().exchangeInvoiceCount()).isZero();
        SaleDashboardStatsDTO stats = saleDashboardService.getDashboard(1).general();
        assertThat(stats.kpis().completedSalesTotal()).isZero();
        assertThat(stats.kpis().completedSalesToday()).isZero();
        assertThat(stats.kpis().amountSoldToday()).isEqualByComparingTo("0");
        assertThat(stats.topProductsToday()).isEmpty();
        // Lo abierto se cuenta, pero como pendiente: es otra cifra.
        assertThat(stats.kpis().pendingSales()).isEqualTo(1);
        assertThat(adminDashboardService.getOverview().topSellingProducts()).isEmpty();
    }

    /** Linea -> unidad fisica, de las lineas que tienen una tomada. */
    private Map<UUID, UUID> unitsByLine(SaleResponseDTO sale) {
        Map<UUID, UUID> units = new HashMap<>();
        sale.details().stream()
                .filter(detail -> detail.item() != null)
                .forEach(detail -> units.put(detail.id(), detail.item().id()));
        return units;
    }

    /** Ninguna unidad fisica esta tomada por dos lineas de ventas abiertas. */
    private void assertNoUnitIsOwnedTwice() {
        assertThat(jdbc.queryForList(
                "SELECT d.item_id FROM sale_detail d JOIN sale s ON s.id = d.sale_id "
                        + "WHERE s.status = 'PENDING' AND d.item_id IS NOT NULL "
                        + "GROUP BY d.item_id HAVING COUNT(*) > 1", UUID.class)).isEmpty();
    }

    private AddSaleDetailRequestDTO normalRequest(Product product, int quantity) {
        return new AddSaleDetailRequestDTO(product.getId(), null, null, quantity, null, null, null);
    }

    private long lotRemaining(Product product, Shop shop) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(a.quantity_remaining), 0) FROM inventory_acquisition a "
                        + "JOIN inventory i ON i.id = a.inventory_id WHERE i.product_id = ? AND i.workshop_id = ?",
                Long.class, product.getId(), shop.workshop().getId());
    }

    // ── Control entrega la unidad ya reservada ───────────────────

    /**
     * La ultima unidad de una pieza: al agregarla queda reservada y el stock
     * libre en cero. Eso no es "sin existencias" para ESTA venta: Control la
     * ve, la entrega y el stock no se mueve otra vez. Otra venta no la toma.
     */
    @Test
    void theLastUnitReservedForTheSaleIsReleasedByControlWithNoFreeStock() {
        Shop shop = shop();
        Product leaf = leaf(shop, 124, 2);
        UUID unit = stocked(shop, leaf, 1).get(0).getId();

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Ultima").id();
        SaleDetailResponseDTO line = onlyLine(saleService.addDetail(saleId, specialRequest(leaf, 1, null)));
        assertThat(line.item().id()).isEqualTo(unit);
        assertThat(itemStatus(unit)).isEqualTo("SOLD");
        assertThat(stockOf(leaf, shop)).isZero();
        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));

        as(shop.control());
        FulfillmentTaskResponseDTO task = onlyTask(
                fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null), line.id());
        assertThat(task.itemId()).isEqualTo(unit);
        assertThat(task.itemBarcode()).isEqualTo(BarcodeIdentity.item(unit));
        // Ninguna OTRA libre: solo importa para cambiarla, no para entregar esta.
        assertThat(task.availableStock()).isZero();
        assertThat(fulfillmentService.getEligibleItems(line.id()))
                .extracting(InventoryItemResponseDTO::id).containsExactly(unit);

        // Como la manda la pantalla: la unidad reservada, por su id.
        FulfillmentTaskResponseDTO released = fulfillmentService.release(
                line.id(), new ReleaseFulfillmentRequestDTO(unit));
        assertThat(released.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(released.itemId()).isEqualTo(unit);
        assertThat(itemStatus(unit)).isEqualTo("SOLD");
        assertThat(stockOf(leaf, shop)).isZero();
        assertThat(lotRemaining(leaf, shop)).isZero();

        as(shop.seller());
        SaleDetailResponseDTO other = onlyLine(saleService.addDetail(
                openUnresolvedSale("Otra").id(), specialRequest(leaf, 1, null)));
        assertThat(other.item()).isNull();
        assertThat(other.fulfillment().type()).isEqualTo(FulfillmentType.TRANSFORMATION);
        assertNoUnitIsOwnedTwice();
    }

    // ── Pieza nueva pedida por sus caracteristicas ───────────────

    /** Dos ventas piden a la vez la misma pieza nueva: un solo producto para las dos. */
    @Test
    void identicalNewSpecificationsRequestedAtOnceShareOneProduct() throws Exception {
        Shop shop = shop();
        Family hoja = seededFamily("HR");
        Map<String, Object> wanted = attributes("largo", 83, "tieneOrificio", false, "cantidadColochos", 1);
        RequestSpecificationDTO request = new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), wanted, 1, PRICE, null, null);

        as(shop.seller());
        UUID first = openUnresolvedSale("Uno").id();
        UUID second = openUnresolvedSale("Dos").id();
        List<Boolean> outcomes = concurrently(shop.seller(),
                () -> saleService.requestSpecification(first, request),
                () -> saleService.requestSpecification(second, request));

        assertThat(outcomes).containsOnly(true);
        assertThat(productWith(hoja, shop.model(), wanted)).isEqualTo(1);
        as(shop.seller());
        assertThat(onlyLine(saleService.get(first)).product().id())
                .isEqualTo(onlyLine(saleService.get(second)).product().id());
    }

    /**
     * Una venta con una pieza de stock (Control) y una pieza nueva (Operador):
     * la nueva es una linea desde que se pide, sin inventario inventado, llega
     * al Operador al enviar, se fabrica sobre la MISMA linea y la factura lleva
     * las dos una vez. Progreso 0/2, 1/2, 2/2.
     */
    @Test
    void aReservedUnitAndANewSpecificationInOneSaleReachReadyTogether() {
        Shop shop = shop();
        Product leaf = leaf(shop, 126, 2);
        UUID unit = stocked(shop, leaf, 1).get(0).getId();
        InventoryItem source = stocked(shop, leaf(shop, 260, 3), 1).get(0);
        Family hoja = seededFamily("HR");
        Map<String, Object> wanted = attributes("largo", 84, "tieneOrificio", false, "cantidadColochos", 1);
        BigDecimal newPrice = new BigDecimal("450.00");
        long productsBefore = products();

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Mixta").id();
        saleService.addDetail(saleId, specialRequest(leaf, 1, null));
        SaleResponseDTO sale = saleService.requestSpecification(saleId, new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), wanted, 1, newPrice, null, null));

        SaleDetailResponseDTO fromStock = lineByType(sale, FulfillmentType.STOCK_RELEASE);
        SaleDetailResponseDTO toFabricate = lineByType(sale, FulfillmentType.TRANSFORMATION);
        UUID newProduct = toFabricate.product().id();
        assertThat(products()).isEqualTo(productsBefore + 1);
        assertThat(inventoryCount(newProduct)).isZero();
        assertThat(toFabricate.item()).isNull();
        assertThat(toFabricate.fulfillment().status()).isEqualTo(FulfillmentStatus.PENDING);
        assertThat(toFabricate.price()).isEqualByComparingTo(newPrice);
        assertThat(sale.total()).isEqualByComparingTo(PRICE.add(newPrice));
        assertThat(fromStock.item().id()).isEqualTo(unit);
        assertThat(stockOf(leaf, shop)).isZero();
        // Releerla, como al recargar, trae las mismas lineas.
        assertThat(saleService.get(saleId).details()).extracting(SaleDetailResponseDTO::id)
                .containsExactlyInAnyOrder(fromStock.id(), toFabricate.id());

        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        assertReadiness(shop, saleId, SaleFulfillmentStatus.IN_PROGRESS, 0);
        as(shop.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null))
                .extracting(FulfillmentTaskResponseDTO::id).contains(toFabricate.id());

        as(shop.control());
        fulfillmentService.release(fromStock.id(), new ReleaseFulfillmentRequestDTO(unit));
        assertReadiness(shop, saleId, SaleFulfillmentStatus.IN_PROGRESS, 1);

        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                toFabricate.id(), new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(done.id()).isEqualTo(toFabricate.id());
        assertThat(productOfItem(done.itemId())).isEqualTo(newProduct);
        assertReadiness(shop, saleId, SaleFulfillmentStatus.READY_TO_COMPLETE, 2);

        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(saleId);
        assertThat(completed.details()).extracting(SaleDetailResponseDTO::id)
                .containsExactlyInAnyOrder(fromStock.id(), toFabricate.id());
        assertThat(lineById(completed, toFabricate.id()).item().id()).isEqualTo(done.itemId());
        assertThat(lineById(completed, toFabricate.id()).workResult()).isNotNull();
        assertThat(completed.total()).isEqualByComparingTo(PRICE.add(newPrice));
        assertThat(linesWithItem(source.getId())).isZero();
        assertThat(stockOf(leaf, shop)).isZero();
    }

    // ── Placa para los servicios ─────────────────────────────────

    /** Un servicio se hace sobre un vehiculo: sin placa no se solicita. */
    @Test
    void aServiceCannotBeRequestedForASaleWithoutAPlate() {
        Shop shop = shop();
        Product installation = job(shop, "CAMBIO DE BUJES");

        as(shop.seller());
        UUID saleId = openSaleWithoutPlate("Sin placa").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId, serviceRequest(installation, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("placa del vehiculo es obligatoria");
        // En blanco es lo mismo que nada.
        assertThatThrownBy(() -> saleService.addDetail(saleId, serviceRequest(installation, "   ")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("placa del vehiculo es obligatoria");

        SaleResponseDTO sale = saleService.get(saleId);
        assertThat(sale.details()).isEmpty();
        assertThat(sale.plate()).isNull();
        assertThat(sale.total()).isEqualByComparingTo("0");
    }

    /** La placa que falta se registra en el mismo paso que el servicio y llega al Operador. */
    @Test
    void theMissingPlateIsRegisteredWithTheServiceAndReachesTheOperator() {
        Shop shop = shop();
        Product installation = job(shop, "CAMBIO DE BUJES");

        as(shop.seller());
        UUID saleId = openSaleWithoutPlate("Placa nueva").id();
        SaleResponseDTO sale = saleService.addDetail(saleId, serviceRequest(installation, "  p512klm "));
        assertThat(sale.plate()).isEqualTo("P512KLM");
        UUID line = onlyLine(sale).id();
        assertThat(onlyLine(sale).fulfillment().type()).isEqualTo(FulfillmentType.SERVICE);
        assertThat(jdbc.queryForObject("SELECT plate FROM sale WHERE id = ?", String.class, saleId))
                .isEqualTo("P512KLM");

        // Con la placa registrada, el siguiente servicio no la pide ni la cambia.
        sale = saleService.addDetail(saleId, serviceRequest(installation, null));
        assertThat(sale.details()).hasSize(2);
        sale = saleService.addDetail(saleId, serviceRequest(installation, "P512KLM"));
        assertThat(sale.details()).hasSize(3);
        assertThatThrownBy(() -> saleService.addDetail(saleId, serviceRequest(installation, "C999XYZ")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("P512KLM");
        assertThat(saleService.get(saleId).plate()).isEqualTo("P512KLM");

        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        as(shop.operator());
        FulfillmentTaskResponseDTO task = onlyTask(
                fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), line);
        assertThat(task.plate()).isEqualTo("P512KLM");
        assertThat(task.customerName()).isEqualToIgnoringCase("Placa nueva");
        assertThat(task.product().name()).isEqualTo(installation.getName());
        assertThat(fulfillmentService.get(line).plate()).isEqualTo("P512KLM");
    }

    /** Lo fisico se vende sin placa: la exige solo el servicio. */
    @Test
    void aProductOnlySaleNeedsNoPlate() {
        Shop shop = shop();
        Product screw = normalProduct(shop, "TORNILLO");
        stocked(shop, screw, 3);
        Product leaf = leaf(shop, 135, 2);
        stocked(shop, leaf, 1);

        as(shop.seller());
        UUID saleId = openSaleWithoutPlate("Mostrador").id();
        saleService.addDetail(saleId, new AddSaleDetailRequestDTO(
                screw.getId(), null, null, 2, null, null, null));
        saleService.addDetail(saleId, specialRequest(leaf, 1, null));
        SaleResponseDTO sale = saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));

        assertThat(sale.plate()).isNull();
        assertThat(sale.details()).hasSize(2);
        assertThat(lineByType(sale, FulfillmentType.STOCK_RELEASE).fulfillment().status())
                .isEqualTo(FulfillmentStatus.QUEUED);
    }

    /**
     * Un servicio pedido antes de exigir la placa al agregarlo, en una venta
     * abierta sin placa: el envio la pide y la registra.
     */
    @Test
    void aServiceAddedBeforeThePlateRuleIsOnlySentWithAPlate() {
        Shop shop = shop();
        Product installation = job(shop, "ALINEACION");

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Anterior").id();
        UUID line = onlyLine(saleService.addDetail(saleId, serviceRequest(installation, null))).id();
        // Como quedaron las ventas abiertas de antes de la regla.
        jdbc.update("UPDATE sale SET plate = NULL WHERE id = ?", saleId);

        assertThatThrownBy(() -> saleService.dispatch(saleId, new DispatchSaleRequestDTO(null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("placa del vehiculo es obligatoria");
        assertThat(onlyLine(saleService.get(saleId)).fulfillment().status())
                .isEqualTo(FulfillmentStatus.PENDING);

        SaleResponseDTO sale = saleService.dispatch(saleId, new DispatchSaleRequestDTO(null, "p777aaa"));
        assertThat(sale.plate()).isEqualTo("P777AAA");
        assertThat(lineById(sale, line).fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);
    }

    /** Otro taller no puede ponerle placa a nuestra venta ni ver la del servicio. */
    @Test
    void anotherWorkshopCannotRegisterOrReadThePlateOfOurService() {
        Shop shop = shop();
        Shop neighbour = shop();
        Product ours = job(shop, "REVISION");
        Product theirs = job(neighbour, "REVISION");

        as(shop.seller());
        UUID emptySale = openSaleWithoutPlate("Nuestra").id();
        UUID servedSale = openSaleWithoutPlate("Atendida").id();
        UUID line = onlyLine(saleService.addDetail(servedSale, serviceRequest(ours, "P100AAA"))).id();
        saleService.dispatch(servedSale, new DispatchSaleRequestDTO(null));

        as(neighbour.seller());
        assertThatThrownBy(() -> saleService.addDetail(emptySale, serviceRequest(theirs, "H000AAA")))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> saleService.dispatch(emptySale, new DispatchSaleRequestDTO(null, "H000AAA")))
                .isInstanceOf(EntityNotFoundException.class);
        as(neighbour.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null)).isEmpty();
        assertThatThrownBy(() -> fulfillmentService.get(line))
                .isInstanceOf(EntityNotFoundException.class);

        as(shop.seller());
        assertThat(saleService.get(emptySale).plate()).isNull();
        assertThat(saleService.get(servedSale).plate()).isEqualTo("P100AAA");
    }

    private AddSaleDetailRequestDTO serviceRequest(Product service, String plate) {
        return new AddSaleDetailRequestDTO(
                service.getId(), null, null, 1, new BigDecimal("150.00"), null, null, plate);
    }

    // ── Lineas directas: siguen como siempre ─────────────────────

    /**
     * La caja no puede senalar la unidad de una pieza especial: ni directa ni
     * como salida de un trabajo. Si pudiera, venderia sin pasar por Control.
     */
    @Test
    void aSpecialUnitCannotBeChosenFromTheTill() {
        Shop shop = shop();
        Product leaf = leaf(shop, 135, 2);
        InventoryItem unit = stocked(shop, leaf, 1).get(0);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Directo").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId,
                new AddSaleDetailRequestDTO(leaf.getId(), unit.getId(), null, null, PRICE, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Control entrega la unidad");

        assertThat(itemStatus(unit.getId())).isEqualTo("AVAILABLE");
        assertThat(stockOf(leaf, shop)).isEqualTo(1);
        assertThat(saleService.get(saleId).details()).isEmpty();

        // Por la comanda si: la misma unidad la entrega Control.
        UUID line = onlyLine(saleService.dispatch(
                saleService.addDetail(saleId, specialRequest(leaf, 1, null)).id(),
                new DispatchSaleRequestDTO(null))).id();
        assertThat(onlyLine(saleService.get(saleId)).fulfillment().area()).isEqualTo(FulfillmentArea.CONTROL);
        as(shop.control());
        assertThat(fulfillmentService.release(line, new ReleaseFulfillmentRequestDTO(null)).itemId())
                .isEqualTo(unit.getId());
    }

    @Test
    void instructionsAreRejectedOnADirectLine() {
        Shop shop = shop();
        Product screw = normalProduct(shop, "TORNILLO");
        stocked(shop, screw, 3);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Instr").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId,
                new AddSaleDetailRequestDTO(screw.getId(), null, null, 1, null, null, "algo")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("solo aplican");
        assertThat(stockOf(screw, shop)).isEqualTo(3);
    }

    // ── 12-16. Anular con servicios ──────────────────────────────

    /** A. Sin empezar: se anula y no se fabrica ningun trabajo. */
    @Test
    void aQueuedServiceIsCancelledWithoutInventingWork() {
        Shop shop = shop();
        Product installation = job(shop, "CAMBIO");

        as(shop.seller());
        UUID saleId = saleService.addDetail(openUnresolvedSale("Cola").id(),
                new AddSaleDetailRequestDTO(installation.getId(), null, null, 1,
                        new BigDecimal("80.00"), null, null)).id();
        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));

        SaleResponseDTO cancelled = saleService.cancel(saleId, new CancelSaleRequestDTO("Ya no lo quiere"));

        assertThat(cancelled.status()).isEqualTo(SaleStatus.CANCELLED);
        assertThat(onlyLine(cancelled).fulfillment().status()).isEqualTo(FulfillmentStatus.CANCELLED);
        assertThat(jobLogsOf(installation)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_result wr JOIN job_log l ON l.id = wr.job_log_id "
                        + "JOIN job j ON j.id = l.job_id WHERE j.product_id = ?",
                Long.class, installation.getId())).isZero();
        // G. Nada queda colgando en la cola del Operador.
        as(shop.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null)).isEmpty();
    }

    /** B. En curso: no se anula, y no se inventa un trabajo terminado. */
    @Test
    void aServiceInProgressBlocksCancellationWithoutFabricatingWork() {
        Shop shop = shop();
        Product installation = job(shop, "ALINEADO");

        as(shop.seller());
        UUID saleId = saleService.addDetail(openUnresolvedSale("Curso2").id(),
                new AddSaleDetailRequestDTO(installation.getId(), null, null, 1,
                        new BigDecimal("80.00"), null, null)).id();
        UUID line = onlyLine(saleService.dispatch(saleId, new DispatchSaleRequestDTO(null))).id();
        as(shop.operator());
        fulfillmentService.start(line);

        as(shop.seller());
        assertThatThrownBy(() -> saleService.cancel(saleId, new CancelSaleRequestDTO("Ya no")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("trabajando");
        assertThat(onlyLine(saleService.get(saleId)).fulfillment().status())
                .isEqualTo(FulfillmentStatus.IN_PROGRESS);
        assertThat(jobLogsOf(installation)).isZero();
    }

    /**
     * C-F. Ya realizado: anular se rechaza. La mano de obra ocurrio, y anular
     * solo borraria la constancia. El JobLog y su trabajo quedan como estaban,
     * y no se mueve ni el cobro ni el stock de las demas lineas.
     */
    @Test
    void aPerformedServiceCannotBeCancelledAndItsHistoryStays() {
        Shop shop = shop();
        Product installation = job(shop, "SOLDADO");
        Product leaf = leaf(shop, 165, 2);
        stocked(shop, leaf, 1);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Hecho2").id();
        saleService.addDetail(saleId, specialRequest(leaf, 1, null));
        saleService.addDetail(saleId, new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("90.00"), null, null));
        SaleResponseDTO dispatched = saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));
        UUID serviceLine = lineOf(dispatched, FulfillmentType.SERVICE);
        UUID controlLine = lineOf(dispatched, FulfillmentType.STOCK_RELEASE);
        as(shop.control());
        fulfillmentService.release(controlLine, new ReleaseFulfillmentRequestDTO(null));
        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.completeService(serviceLine);

        as(shop.seller());
        assertThatThrownBy(() -> saleService.cancel(saleId, new CancelSaleRequestDTO("Se arrepintio")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("servicio ya realizado");

        SaleResponseDTO after = saleService.get(saleId);
        assertThat(after.status()).isEqualTo(SaleStatus.PENDING);
        assertThat(after.paymentStatus().name()).isEqualTo("PENDING");
        assertThat(lineById(after, serviceLine).fulfillment().status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(lineById(after, serviceLine).fulfillment().jobLogId()).isEqualTo(done.jobLogId());
        assertThat(lineById(after, serviceLine).workResult()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_log WHERE id = ?", Long.class, done.jobLogId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM work_result WHERE job_log_id = ?",
                String.class, done.jobLogId())).isEqualTo("PENDING");
        // La pieza entregada sigue en la venta: no se devolvio a medias.
        assertThat(itemStatus(lineById(after, controlLine).item().id())).isEqualTo("SOLD");
        assertThat(stockOf(leaf, shop)).isZero();
        // Sigue pudiendo facturarse, y entonces el trabajo pasa a vendido.
        completeAsCf(saleId);
        assertThat(jdbc.queryForObject("SELECT status FROM work_result WHERE job_log_id = ?",
                String.class, done.jobLogId())).isEqualTo("SOLD");
    }

    // ── 17-22. Revision de precio ────────────────────────────────

    private static final BigDecimal AUTHORIZED = new BigDecimal("200.00");

    private UUID fabricationRequest(Shop shop, Product target) {
        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Precio").id(),
                new AddSaleDetailRequestDTO(target.getId(), null, null, 1, AUTHORIZED, null, null));
        return onlyLine(saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null))).id();
    }

    /** A. La pieza de origen cuesta menos que lo autorizado: se fabrica. */
    @Test
    void aSourceCheaperThanTheAuthorizedPriceIsFabricated() {
        Shop shop = shop();
        Product target = leaf(shop, 90, 2);
        emptyInventory(shop, target);
        InventoryItem cheap = stockedAtCost(shop, leaf(shop, 180, 2), 1, new BigDecimal("150.00")).get(0);
        UUID line = fabricationRequest(shop, target);

        as(shop.operator());
        assertThat(fulfillmentService.getSources(line, null))
                .filteredOn(source -> source.item().id().equals(cheap.getId()))
                .singleElement()
                .satisfies(source -> assertThat(source.withinAuthorizedPrice()).isTrue());
        assertThat(fulfillmentService.transform(line, new FulfillTransformationRequestDTO(cheap.getId(), null))
                .status()).isEqualTo(FulfillmentStatus.FULFILLED);
    }

    /** B. Costo exactamente igual al precio: la regla de siempre lo permite. */
    @Test
    void aSourceCostingExactlyTheAuthorizedPriceIsFabricated() {
        Shop shop = shop();
        Product target = leaf(shop, 90, 2);
        emptyInventory(shop, target);
        InventoryItem exact = stockedAtCost(shop, leaf(shop, 180, 2), 1, AUTHORIZED).get(0);
        UUID line = fabricationRequest(shop, target);

        as(shop.operator());
        assertThat(fulfillmentService.transform(line, new FulfillTransformationRequestDTO(exact.getId(), null))
                .status()).isEqualTo(FulfillmentStatus.FULFILLED);
    }

    /**
     * C-H. La pieza de origen cuesta mas: no se fabrica nada, la solicitud
     * vuelve al vendedor con el minimo, la venta no se puede completar, el
     * operador no puede tocar el precio, y tras la revision se fabrica.
     */
    @Test
    void aSourceAboveTheAuthorizedPriceWaitsForTheSellersReview() {
        Shop shop = shop();
        Product target = leaf(shop, 90, 2);
        emptyInventory(shop, target);
        InventoryItem expensive = stockedAtCost(shop, leaf(shop, 180, 2), 1, new BigDecimal("260.00")).get(0);
        UUID line = fabricationRequest(shop, target);
        long transformationsBefore = transformations();
        long itemsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item", Long.class);

        as(shop.operator());
        assertThat(fulfillmentService.getSources(line, null))
                .filteredOn(source -> source.item().id().equals(expensive.getId()))
                .singleElement()
                .satisfies(source -> assertThat(source.withinAuthorizedPrice()).isFalse());
        FulfillmentTaskResponseDTO blocked = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(expensive.getId(), null));

        // C/G. Bloqueada para el vendedor, sin nada fisico hecho.
        assertThat(blocked.status()).isEqualTo(FulfillmentStatus.BLOCKED);
        assertThat(blocked.blockReason()).isEqualTo(FulfillmentBlockReason.PRICE_REVIEW);
        assertThat(transformations()).isEqualTo(transformationsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item", Long.class)).isEqualTo(itemsBefore);
        assertThat(itemStatus(expensive.getId())).isEqualTo("AVAILABLE");
        // El operador no puede destrabarla por su cuenta.
        assertThatThrownBy(() -> fulfillmentService.start(line))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("revise el precio");
        assertThatThrownBy(() -> fulfillmentService.block(line, new BlockFulfillmentRequestDTO("Sin material")))
                .isInstanceOf(ValidationException.class);

        // El vendedor ve el motivo y el minimo.
        as(shop.seller());
        SaleResponseDTO sale = saleService.get(saleIdOf(line));
        SaleDetailResponseDTO waiting = onlyLine(sale);
        // Bloqueada sigue siendo la misma linea de la cuenta, con su importe.
        assertThat(waiting.id()).isEqualTo(line);
        assertThat(sale.total()).isEqualByComparingTo(AUTHORIZED);
        assertThat(waiting.fulfillment().blockReason()).isEqualTo(FulfillmentBlockReason.PRICE_REVIEW);
        assertThat(waiting.fulfillment().requiredPrice()).isEqualByComparingTo("260.00");
        assertThat(waiting.price()).isEqualByComparingTo(AUTHORIZED);
        // H. Sin revisar, la venta no se completa.
        assertThat(sale.fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> completeAsCf(sale.id())).isInstanceOf(ValidationException.class);

        // Bajo el minimo no se acepta.
        assertThatThrownBy(() -> saleService.updateRequestPrice(line, new UpdateRequestPriceDTO(new BigDecimal("250.00"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("260.00");

        // E/F. Con un precio que lo cubre, vuelve a la cola del Operador.
        SaleResponseDTO revised = saleService.updateRequestPrice(line, new UpdateRequestPriceDTO(new BigDecimal("320.00")));
        assertThat(onlyLine(revised).id()).isEqualTo(line);
        assertThat(onlyLine(revised).price()).isEqualByComparingTo("320.00");
        assertThat(onlyLine(revised).subTotal()).isEqualByComparingTo("320.00");
        assertThat(revised.total()).isEqualByComparingTo("320.00");
        assertThat(onlyLine(revised).fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);
        assertThat(onlyLine(revised).fulfillment().blockReason()).isNull();

        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(expensive.getId(), null));
        assertThat(done.status()).isEqualTo(FulfillmentStatus.FULFILLED);

        // La factura lleva el precio que aprobo el vendedor, una vez.
        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(saleIdOf(line));
        assertThat(completed.details()).hasSize(1);
        assertThat(onlyLine(completed).id()).isEqualTo(line);
        assertThat(completed.total()).isEqualByComparingTo("320.00");
    }

    /** El precio solo se revisa sin enviar o en revision: no sobre trabajo en cola. */
    @Test
    void thePriceCannotBeChangedOnceTheWorkshopHasTheRequest() {
        Shop shop = shop();
        Product target = leaf(shop, 90, 2);
        emptyInventory(shop, target);
        UUID line = fabricationRequest(shop, target);

        as(shop.seller());
        assertThatThrownBy(() -> saleService.updateRequestPrice(line, new UpdateRequestPriceDTO(new BigDecimal("300.00"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("revision de precio");
        assertThat(onlyLine(saleService.get(saleIdOf(line))).price()).isEqualByComparingTo(AUTHORIZED);
    }

    /** Antes de enviar, el vendedor corrige el precio de lo que pidio. */
    @Test
    void aPendingRequestPriceCanBeCorrectedBeforeDispatch() {
        Shop shop = shop();
        Product leaf = leaf(shop, 90, 2);
        emptyInventory(shop, leaf);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Corrige").id(),
                new AddSaleDetailRequestDTO(leaf.getId(), null, null, 2, AUTHORIZED, null, null));
        UUID first = sale.details().get(0).id();
        SaleResponseDTO corrected = saleService.updateRequestPrice(first, new UpdateRequestPriceDTO(new BigDecimal("210.00")));
        assertThat(corrected.total()).isEqualByComparingTo("410.00");
        // Nunca por debajo del costo del inventario pedido (Q100).
        assertThatThrownBy(() -> saleService.updateRequestPrice(first, new UpdateRequestPriceDTO(new BigDecimal("90.00"))))
                .isInstanceOf(ValidationException.class);
    }

    /** 22. Una pieza nueva sigue la misma regla de costo. */
    @Test
    void aNewSpecificationGoesThroughTheSamePriceReview() {
        Shop shop = shop();
        InventoryItem expensive = stockedAtCost(shop, leaf(shop, 200, 3), 1, new BigDecimal("400.00")).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.requestSpecification(openUnresolvedSale("Nueva").id(),
                new RequestSpecificationDTO(seededFamily("HR").getId(), shop.model().getId(),
                        attributes("largo", 77, "tieneOrificio", false, "cantidadColochos", 1),
                        1, AUTHORIZED, null, null));
        UUID line = onlyLine(saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null))).id();

        as(shop.operator());
        FulfillmentTaskResponseDTO blocked = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(expensive.getId(), null));
        assertThat(blocked.blockReason()).isEqualTo(FulfillmentBlockReason.PRICE_REVIEW);
        assertThat(onlyLineOf(saleIdOf(line), shop).fulfillment().requiredPrice()).isEqualByComparingTo("400.00");
    }

    /** 23. Con stock parcial, la revision solo afecta a la unidad que la necesita. */
    @Test
    void partialStockOnlySendsTheExpensiveUnitToPriceReview() {
        Shop shop = shop();
        Product target = leaf(shop, 95, 2);
        stocked(shop, target, 1);
        InventoryItem cheap = stockedAtCost(shop, leaf(shop, 190, 2), 1, new BigDecimal("120.00")).get(0);
        InventoryItem expensive = stockedAtCost(shop, leaf(shop, 191, 2), 1, new BigDecimal("700.00")).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(openUnresolvedSale("Parcial").id(),
                new AddSaleDetailRequestDTO(target.getId(), null, null, 3, PRICE, null, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        List<UUID> toFabricate = sale.details().stream()
                .filter(detail -> detail.fulfillment().type() == FulfillmentType.TRANSFORMATION)
                .map(SaleDetailResponseDTO::id)
                .toList();
        assertThat(toFabricate).hasSize(2);

        as(shop.operator());
        assertThat(fulfillmentService.transform(toFabricate.get(0),
                new FulfillTransformationRequestDTO(cheap.getId(), null)).status())
                .isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(fulfillmentService.transform(toFabricate.get(1),
                new FulfillTransformationRequestDTO(expensive.getId(), null)).blockReason())
                .isEqualTo(FulfillmentBlockReason.PRICE_REVIEW);

        as(shop.seller());
        SaleResponseDTO after = saleService.get(sale.id());
        // Las tres unidades, cada una una vez, al precio autorizado.
        assertThat(after.details()).hasSize(3);
        assertThat(after.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));
        assertThat(after.details()).filteredOn(detail -> detail.fulfillment().status() == FulfillmentStatus.BLOCKED)
                .hasSize(1);
    }

    /**
     * 42. El vendedor revisa el precio a la vez que el operador reintenta.
     * Gane quien gane el cerrojo de la venta, nunca se fabrica por debajo del
     * precio aprobado ni salen dos piezas.
     */
    @Test
    void aPriceReviewRacingAnOperatorRetryNeverFabricatesBelowCost() throws Exception {
        Shop shop = shop();
        Product target = leaf(shop, 90, 2);
        emptyInventory(shop, target);
        InventoryItem expensive = stockedAtCost(shop, leaf(shop, 180, 2), 1, new BigDecimal("260.00")).get(0);
        UUID line = fabricationRequest(shop, target);
        as(shop.operator());
        fulfillmentService.transform(line, new FulfillTransformationRequestDTO(expensive.getId(), null));

        List<Boolean> outcomes = concurrentlyAs(List.of(
                Map.entry(shop.seller(), () -> saleService.updateRequestPrice(
                        line, new UpdateRequestPriceDTO(new BigDecimal("300.00")))),
                Map.entry(shop.operator(), () -> fulfillmentService.transform(
                        line, new FulfillTransformationRequestDTO(expensive.getId(), null)))));
        assertThat(outcomes).containsOnly(true);

        as(shop.seller());
        SaleDetailResponseDTO after = onlyLine(saleService.get(saleIdOf(line)));
        assertThat(after.price()).isEqualByComparingTo("300.00");
        long inputs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transformation_item WHERE item_id = ? AND role = 'INPUT'",
                Long.class, expensive.getId());
        if (after.fulfillment().status() == FulfillmentStatus.FULFILLED) {
            // La revision gano: se fabrico con el precio ya aprobado.
            assertThat(inputs).isEqualTo(1L);
        } else {
            // El reintento gano: volvio a quedar en revision y la revision la
            // devolvio a la cola. No se fabrico nada.
            assertThat(after.fulfillment().status()).isEqualTo(FulfillmentStatus.QUEUED);
            assertThat(inputs).isZero();
            assertThat(itemStatus(expensive.getId())).isEqualTo("AVAILABLE");
        }
    }

    /** Dos operadores reintentan a la vez tras la revision: una sola pieza. */
    @Test
    void twoRetriesAfterTheReviewProduceASinglePiece() throws Exception {
        Shop shop = shop();
        Product target = leaf(shop, 90, 2);
        emptyInventory(shop, target);
        InventoryItem expensive = stockedAtCost(shop, leaf(shop, 180, 2), 1, new BigDecimal("260.00")).get(0);
        UUID line = fabricationRequest(shop, target);
        as(shop.operator());
        fulfillmentService.transform(line, new FulfillTransformationRequestDTO(expensive.getId(), null));
        as(shop.seller());
        saleService.updateRequestPrice(line, new UpdateRequestPriceDTO(new BigDecimal("300.00")));

        List<Boolean> outcomes = concurrentlyAs(List.of(
                Map.entry(shop.operator(), () -> fulfillmentService.transform(
                        line, new FulfillTransformationRequestDTO(expensive.getId(), null))),
                Map.entry(shop.operator2(), () -> fulfillmentService.transform(
                        line, new FulfillTransformationRequestDTO(expensive.getId(), null)))));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transformation_item WHERE item_id = ? AND role = 'INPUT'",
                Long.class, expensive.getId())).isEqualTo(1L);
    }

    // ── Fabricar desde material liso ─────────────────────────────

    /**
     * Lo que hace el Operador en "Fabricar pieza solicitada" con una barra de
     * material liso: la hoja pedida sale de la barra, la barra se consume una
     * vez, el sobrante queda en stock, y la MISMA linea recibe la pieza y su
     * WorkResult. La venta no gana lineas ni importe.
     */
    @Test
    void aSmoothBarIsCutIntoTheRequestedLeafOnTheSameLine() {
        Shop shop = shop();
        Product target = leaf(shop, 87, 1);
        InventoryItem bar = stocked(shop, smoothBar(270), 2).get(0);
        UUID line = fabricationRequest(shop, target);
        UUID sale = saleIdOf(line);
        BigDecimal total = jdbc.queryForObject("SELECT total FROM sale WHERE id = ?", BigDecimal.class, sale);

        as(shop.operator());
        FulfillmentSourceDTO offered = fulfillmentService.getSources(line, null).stream()
                .filter(source -> source.item().id().equals(bar.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(offered.acceptsOutputCurlCount()).isFalse();
        assertThat(offered.remainderWithoutCurls()).isTrue();
        assertThat(offered.remainderLength()).isEqualTo(183.0);

        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(bar.getId(), null));

        assertThat(done.id()).isEqualTo(line);
        assertThat(done.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        UUID transformation = jdbc.queryForObject(
                "SELECT fulfillment_transformation_id FROM sale_detail WHERE id = ?", UUID.class, line);
        assertThat(roleCount(transformation, "INPUT")).isEqualTo(1L);
        assertThat(roleCount(transformation, "OUTPUT")).isEqualTo(1L);
        assertThat(roleCount(transformation, "REMAINDER")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transformation_item WHERE item_id = ?", Long.class, bar.getId()))
                .isEqualTo(1L);
        assertThat(itemRepository.findById(bar.getId()).orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE id = ?", Integer.class, bar.getInventory().getId()))
                .isEqualTo(1);

        UUID output = jdbc.queryForObject(
                "SELECT item_id FROM transformation_item WHERE transformation_id = ? AND role = 'OUTPUT'",
                UUID.class, transformation);
        assertThat(jdbc.queryForMap("SELECT item_id, work_result_id FROM sale_detail WHERE id = ?", line))
                .containsEntry("item_id", output)
                .containsEntry("work_result_id", jdbc.queryForObject(
                        "SELECT id FROM work_result WHERE item_id = ?", UUID.class, output));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_result WHERE item_id = ?", Long.class, output)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_detail WHERE sale_id = ?", Long.class, sale)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT total FROM sale WHERE id = ?", BigDecimal.class, sale))
                .isEqualByComparingTo(total);
        as(shop.seller());
        assertThat(saleService.get(sale).fulfillmentStatus()).isEqualTo(SaleFulfillmentStatus.READY_TO_COMPLETE);
    }

    /**
     * G. En una comanda nadie indica los colochos del sobrante: vuelve al
     * inventario como pieza SIN colochos (cero), con su largo real, del taller
     * y la familia de la pieza de origen. El trabajo facturable es solo la
     * pieza pedida.
     */
    @Test
    void aComandaLeftoverReturnsToStockWithoutCurls() {
        Shop shop = shop();
        Product target = leaf(shop, 87, 1);
        InventoryItem bar = stocked(shop, smoothBar(270), 2).get(0);
        UUID line = fabricationRequest(shop, target);
        long workResultsBefore = workResults();

        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(bar.getId(), null));

        assertThat(done.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        Map<String, Object> leftover = leftoverOf(line);
        assertThat(leftover)
                .containsEntry("status", "AVAILABLE")
                .containsEntry("curls", "0")
                .containsEntry("largo", "183.0")
                .containsEntry("family_id", seededFamily("ML").getId())
                .containsEntry("workshop_id", shop.workshop().getId());
        assertThat(jdbc.queryForObject("SELECT stock FROM inventory WHERE id = ?", Integer.class,
                leftover.get("inventory_id"))).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_result WHERE item_id = ?", Long.class, leftover.get("item_id")))
                .isZero();
        assertThat(workResults()).isEqualTo(workResultsBefore + 1);
    }

    /** H. Sin sobrante no nace ninguna pieza de mas. */
    @Test
    void aComandaTransformationWithoutLeftoverCreatesNone() {
        Shop shop = shop();
        Product target = leaf(shop, 140, 2);
        InventoryItem source = stocked(shop, leaf(shop, 180, 3), 1).get(0);
        UUID line = fabricationRequest(shop, target);
        long itemsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item", Long.class);

        as(shop.operator());
        fulfillmentService.transform(line, new FulfillTransformationRequestDTO(source.getId(), null));

        UUID transformation = jdbc.queryForObject(
                "SELECT fulfillment_transformation_id FROM sale_detail WHERE id = ?", UUID.class, line);
        assertThat(roleCount(transformation, "REMAINDER")).isZero();
        assertThat(roleCount(transformation, "OUTPUT")).isEqualTo(1L);
        // Solo nace la pieza fabricada.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item", Long.class))
                .isEqualTo(itemsBefore + 1);
    }

    /** I. La transformacion manual sigue exigiendo los colochos del sobrante. */
    @Test
    void aManualTransformationStillRequiresTheLeftoverCurls() {
        Shop shop = shop();
        Product target = leaf(shop, 100, 2);
        InventoryItem source = stocked(shop, leaf(shop, 200, 3), 1).get(0);

        as(shop.operator());
        assertThatThrownBy(() -> transformationService.create(new CreateTransformationRequestDTO(
                source.getId(), null, target.getId(), null, null, 2, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("colochos del sobrante");
        assertThat(itemStatus(source.getId())).isEqualTo("AVAILABLE");

        var manual = transformationService.create(new CreateTransformationRequestDTO(
                source.getId(), null, target.getId(), null, null, 2, 1, null));
        UUID remainder = jdbc.queryForObject(
                "SELECT item_id FROM transformation_item WHERE transformation_id = ? AND role = 'REMAINDER'",
                UUID.class, manual.id());
        assertThat(curlsOfProduct(productOfItem(remainder))).isEqualTo("1");
    }

    /** Una pieza que no alcanza el largo pedido no se ofrece y, forzada, se rechaza sin mover nada. */
    @Test
    void aSourceTooShortForTheRequestIsRejectedAndNothingMoves() {
        Shop shop = shop();
        Product target = leaf(shop, 87, 1);
        InventoryItem shortBar = stocked(shop, smoothBar(60), 1).get(0);
        UUID line = fabricationRequest(shop, target);
        long transformationsBefore = transformations();
        long workResultsBefore = workResults();

        as(shop.operator());
        assertThat(fulfillmentService.getSources(line, null))
                .noneMatch(source -> source.item().id().equals(shortBar.getId()));
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(shortBar.getId(), null)))
                .isInstanceOf(ValidationException.class);

        assertUntouched(line, shortBar, 1, transformationsBefore, workResultsBefore);
    }

    /**
     * Doble clic: dos peticiones del MISMO operador a la vez. El cerrojo de la
     * venta las ordena; la segunda encuentra la solicitud cumplida y se
     * rechaza. Una transformacion, un consumo, una salida, un WorkResult.
     */
    @Test
    void theSameOperatorFabricatingTwiceAtOnceMakesASinglePiece() throws Exception {
        Shop shop = shop();
        Product target = leaf(shop, 87, 1);
        InventoryItem bar = stocked(shop, smoothBar(270), 2).get(0);
        UUID line = fabricationRequest(shop, target);
        FulfillTransformationRequestDTO request = new FulfillTransformationRequestDTO(bar.getId(), null);

        List<Boolean> outcomes = concurrentlyAs(List.of(
                Map.entry(shop.operator(), () -> fulfillmentService.transform(line, request)),
                Map.entry(shop.operator(), () -> fulfillmentService.transform(line, request))));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        UUID transformation = jdbc.queryForObject(
                "SELECT fulfillment_transformation_id FROM sale_detail WHERE id = ?", UUID.class, line);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transformation_item WHERE item_id = ? AND role = 'INPUT'",
                Long.class, bar.getId())).isEqualTo(1L);
        assertThat(roleCount(transformation, "OUTPUT")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_result w JOIN transformation_item t ON t.item_id = w.item_id "
                        + "WHERE t.transformation_id = ? AND t.role = 'OUTPUT'",
                Long.class, transformation)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_detail WHERE sale_id = ?", Long.class, saleIdOf(line))).isEqualTo(1L);

        as(shop.operator());
        assertThatThrownBy(() -> fulfillmentService.transform(line, request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La solicitud ya fue cumplida");
    }

    /**
     * Otra sesion retiene la fila de la venta sin confirmar (una consola, una
     * transaccion colgada). Fabricar NO espera para siempre: al cumplirse el
     * tope termina con un error de concurrencia, sin mover nada, y en cuanto
     * se suelta el cerrojo el mismo pedido se fabrica.
     */
    @Test
    void aFabricationWaitingOnALockHeldElsewhereEndsInsteadOfHanging() throws Exception {
        Shop shop = shop();
        Product target = leaf(shop, 87, 1);
        InventoryItem bar = stocked(shop, smoothBar(270), 2).get(0);
        UUID line = fabricationRequest(shop, target);
        UUID sale = saleIdOf(line);
        FulfillTransformationRequestDTO request = new FulfillTransformationRequestDTO(bar.getId(), null);
        long transformationsBefore = transformations();
        long workResultsBefore = workResults();

        as(shop.operator());
        long started = System.nanoTime();
        try (AutoCloseable held = holding(() -> jdbc.queryForList(
                "SELECT id FROM sale WHERE id = ? FOR UPDATE", sale))) {
            assertThatThrownBy(() -> fulfillmentService.transform(line, request))
                    .isInstanceOf(TransientDataAccessException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(TRANSFORM_TIMEOUT_SECONDS + 5L));
        assertUntouched(line, bar, 2, transformationsBefore, workResultsBefore);

        assertThat(fulfillmentService.transform(line, request).status()).isEqualTo(FulfillmentStatus.FULFILLED);
    }

    /**
     * El cerrojo de familia que toma el vendedor al pedir una pieza nueva es
     * `FOR NO KEY UPDATE`: no choca con la clave foranea de un producto nuevo.
     * Fabricar una hoja que deja un sobrante nuevo (otro producto de la misma
     * familia) termina aunque ese cerrojo siga tomado.
     */
    @Test
    void aSellerHoldingTheFamilyLockDoesNotHoldUpAFabrication() throws Exception {
        Shop shop = shop();
        Product target = leaf(shop, 87, 1);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 200, 2), 1).get(0);
        UUID line = fabricationRequest(shop, target);
        long productsBefore = products();

        as(shop.operator());
        try (AutoCloseable held = holding(() -> familyRepository.findByIdForUpdate(seededFamily("HR").getId()))) {
            assertThat(fulfillmentService.transform(
                    line, new FulfillTransformationRequestDTO(source.getId(), null)).status())
                    .isEqualTo(FulfillmentStatus.FULFILLED);
        }
        assertThat(products()).isGreaterThan(productsBefore);
    }

    /** El Operador de otro taller no ve las piezas de origen ni fabrica la solicitud ajena. */
    @Test
    void aNeighbourOperatorCannotFabricateAnotherWorkshopsRequest() {
        Shop shop = shop();
        Shop neighbour = shop();
        Product target = leaf(shop, 87, 1);
        InventoryItem bar = stocked(shop, smoothBar(270), 2).get(0);
        UUID line = fabricationRequest(shop, target);
        long transformationsBefore = transformations();
        long workResultsBefore = workResults();

        as(neighbour.operator());
        assertThatThrownBy(() -> fulfillmentService.getSources(line, null))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(bar.getId(), null)))
                .isInstanceOf(EntityNotFoundException.class);

        assertUntouched(line, bar, 2, transformationsBefore, workResultsBefore);
    }

    /** Una barra de material liso sin modelo: la familia no lo exige. */
    private Product smoothBar(int length) {
        return productRepository.save(Product.builder()
                .family(seededFamily("ML"))
                .name("MATERIAL LISO " + length + " " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes("largo", length, "tieneOrificio", false))
                .build());
    }

    private long roleCount(UUID transformation, String role) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transformation_item WHERE transformation_id = ? AND role = ?",
                Long.class, transformation, role);
    }

    private long workResults() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM work_result", Long.class);
    }

    /** Una fabricacion fallida no deja rastro: solicitud en cola, pieza libre, stock y lotes intactos. */
    private void assertUntouched(
            UUID line, InventoryItem source, int stock, long transformationsBefore, long workResultsBefore) {
        assertThat(jdbc.queryForMap(
                "SELECT fulfillment_status, item_id, work_result_id FROM sale_detail WHERE id = ?", line))
                .containsEntry("fulfillment_status", "QUEUED")
                .containsEntry("item_id", null)
                .containsEntry("work_result_id", null);
        assertThat(itemRepository.findById(source.getId()).orElseThrow().getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
        UUID inventory = source.getInventory().getId();
        assertThat(jdbc.queryForObject("SELECT stock FROM inventory WHERE id = ?", Integer.class, inventory))
                .isEqualTo(stock);
        assertThat(jdbc.queryForObject(
                "SELECT SUM(quantity_remaining) FROM inventory_acquisition WHERE inventory_id = ?",
                Integer.class, inventory)).isEqualTo(stock);
        assertThat(transformations()).isEqualTo(transformationsBefore);
        assertThat(workResults()).isEqualTo(workResultsBefore);
    }

    /**
     * Otra transaccion que toma un cerrojo y lo retiene, como una sesion de
     * consola sin confirmar, hasta que se cierra lo devuelto.
     */
    private AutoCloseable holding(Runnable lock) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> done = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            lock.run();
            held.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        if (!held.await(10, TimeUnit.SECONDS)) {
            holder.shutdownNow();
            throw new IllegalStateException("No se pudo tomar el cerrojo de la prueba");
        }
        return () -> {
            release.countDown();
            try {
                done.get(30, TimeUnit.SECONDS);
            } finally {
                holder.shutdownNow();
            }
        };
    }

    private UUID saleIdOf(UUID line) {
        return jdbc.queryForObject("SELECT sale_id FROM sale_detail WHERE id = ?", UUID.class, line);
    }

    private SaleDetailResponseDTO onlyLineOf(UUID saleId, Shop shop) {
        as(shop.seller());
        return onlyLine(saleService.get(saleId));
    }

    private long jobLogsOf(Product jobProduct) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_log l JOIN job j ON j.id = l.job_id WHERE j.product_id = ?",
                Long.class, jobProduct.getId());
    }

    // ── 69. Sin N+1 ──────────────────────────────────────────────

    /**
     * Publicar el cumplimiento de cada linea no cuesta consultas por linea: la
     * transformacion y el servicio ejecutado se publican por su id, que viaja
     * en la propia fila de la linea.
     */
    @Test
    void readingASaleWithFulfilledRequestsDoesNotGrowWithItsLines() {
        Shop shop = shop();
        UUID one = saleWithFulfilledServices(shop, 1);
        UUID twenty = saleWithFulfilledServices(shop, 20);

        as(shop.seller());
        long withOne = statementsWhen(() -> saleService.get(one));
        long withTwenty = statementsWhen(() -> saleService.get(twenty));

        assertThat(withTwenty - withOne).isLessThanOrEqualTo(CONSTANT_TOLERANCE);
        assertThat(STATEMENTS).noneMatch(sql -> sql.startsWith("select")
                && (sql.contains("from job_log") || sql.contains("from transformation")));
        assertThat(saleService.get(twenty).details())
                .allSatisfy(detail -> assertThat(detail.fulfillment().jobLogId()).isNotNull());
    }

    @Test
    void theWorkshopQueuesAreOneQueryAtAnySize() {
        Shop shop = shop();
        Product installation = job(shop, "COLA");
        Product leaf = leaf(shop, 155, 2);
        stocked(shop, leaf, 25);

        as(shop.seller());
        queueRequests(shop, installation, leaf, 1);
        as(shop.operator());
        long operatorOne = statementsWhen(
                () -> fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null));
        as(shop.control());
        long controlOne = statementsWhen(
                () -> fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null));

        as(shop.seller());
        queueRequests(shop, installation, leaf, 20);
        as(shop.operator());
        long operatorMany = statementsWhen(
                () -> fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null));
        as(shop.control());
        long controlMany = statementsWhen(
                () -> fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null));

        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null)).hasSize(21);
        assertThat(operatorMany - operatorOne).isLessThanOrEqualTo(CONSTANT_TOLERANCE);
        assertThat(controlMany - controlOne).isLessThanOrEqualTo(CONSTANT_TOLERANCE);
    }

    /** "Ventas listas" no paga una consulta por venta listada. */
    @Test
    void theReadySalesListIsConstantInTheNumberOfSales() {
        Shop shop = shop();
        saleWithFulfilledServices(shop, 1);
        as(shop.seller());
        long withOne = statementsWhen(() -> saleService.getReady(null));

        for (int sale = 0; sale < 5; sale++) {
            saleWithFulfilledServices(shop, 2);
        }
        as(shop.seller());
        long withSix = statementsWhen(() -> saleService.getReady(null));

        assertThat(saleService.getReady(null)).hasSize(6);
        assertThat(withSix - withOne).isLessThanOrEqualTo(CONSTANT_TOLERANCE);
    }

    private long statementsWhen(Runnable action) {
        STATEMENTS.clear();
        action.run();
        return STATEMENTS.size();
    }

    private UUID saleWithFulfilledServices(Shop shop, int lines) {
        Product installation = job(shop, "SERVICIO " + lines);
        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Plan " + lines);
        for (int line = 0; line < lines; line++) {
            sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                    installation.getId(), null, null, 1, new BigDecimal("10.00"), null, null));
        }
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        as(shop.operator());
        sale.details().forEach(detail -> fulfillmentService.completeService(detail.id()));
        return sale.id();
    }

    private void queueRequests(Shop shop, Product installation, Product leaf, int count) {
        for (int index = 0; index < count; index++) {
            SaleResponseDTO sale = openUnresolvedSale("Cola " + index);
            sale = saleService.addDetail(sale.id(), specialRequest(leaf, 1, null));
            sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                    installation.getId(), null, null, 1, new BigDecimal("10.00"), null, null));
            saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        }
    }

    // ── Colochos: los decide el vendedor; el Operador solo completa ──

    /**
     * A. "Sí, 4" fijado por la pieza elegida: la linea guarda la decision, el
     * Operador ve 4, no puede usar otra cantidad y la pieza sale con 4. El
     * sobrante de la comanda queda sin colochos.
     */
    @Test
    void aYesWithFourFixedByThePieceIsKeptAndCannotBeChanged() {
        Shop shop = shop();
        Product target = leaf(shop, 100, 4);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 200, 2), 1).get(0);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Colochos").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(target, false, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("lleva 4 colochos");
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(target, true, 3)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("lleva 4 colochos");
        SaleResponseDTO sale = saleService.addDetail(saleId, specialRequest(target, 1, null));
        UUID line = onlyLine(sale).id();
        assertThat(onlyLine(sale).requestedCurls()).isTrue();
        assertThat(onlyLine(sale).requestedCurlCount()).isEqualTo(4);
        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));

        as(shop.operator());
        FulfillmentTaskResponseDTO task = fulfillmentService.get(line);
        assertThat(task.requestedCurls()).isTrue();
        assertThat(task.requestedCurlCount()).isEqualTo(4);
        assertThat(onlySource(line, source.getId()).acceptsOutputCurlCount()).isFalse();
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), 3)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("4 colochos");
        assertThat(itemStatus(source.getId())).isEqualTo("AVAILABLE");

        task = fulfillmentService.transform(line, new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(productOfItem(task.itemId())).isEqualTo(target.getId());
        assertThat(curlsOfProduct(target.getId())).isEqualTo("4");
        assertThat(leftoverOf(line)).containsEntry("curls", "0").containsEntry("largo", "100.0");
    }

    /**
     * A'. "Sí, 4" sobre una pieza del catalogo que no fija sus colochos: la
     * cantidad queda en la linea, el Operador la ve fija y la pieza sale con 4.
     */
    @Test
    void aYesWithFourOnAPieceThatDoesNotFixThemIsKeptOnTheLine() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 180, 2), 1).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(
                openUnresolvedSale("Cuatro").id(), curlRequest(target, true, 4));
        UUID line = onlyLine(sale).id();
        saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        assertThat(requestedCurlsOf(line)).containsEntry("requested_curls", true)
                .containsEntry("requested_curl_count", 4);

        as(shop.operator());
        assertThat(fulfillmentService.get(line).requestedCurlCount()).isEqualTo(4);
        assertThat(onlySource(line, source.getId()).acceptsOutputCurlCount()).isFalse();
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), 2)))
                .isInstanceOf(ValidationException.class);
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), null));

        assertThat(curlsOfProduct(productOfItem(done.itemId()))).isEqualTo("4");
        as(shop.seller());
        SaleDetailResponseDTO billed = onlyLine(saleService.get(sale.id()));
        assertThat(billed.id()).isEqualTo(line);
        assertThat(billed.invoiceDescription()).isEqualTo(target.getName());
        assertThat(billed.price()).isEqualByComparingTo(PRICE);
    }

    /**
     * B'. Una linea guardada con "si" y SIN cantidad antes de que la cantidad
     * fuera obligatoria se sigue cumpliendo: el Operador PUEDE registrar los que
     * lleve la pieza (al menos 1). Lo que registre va a la pieza fabricada; la
     * decision guardada en la linea no cambia, ni el precio, ni la linea, ni el
     * total.
     */
    @Test
    void aStoredYesWithoutCountFromBeforeTheRuleLetsTheOperatorRecordOne() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 180, 2), 1).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(
                openUnresolvedSale("Abierta").id(), curlRequest(target, true, 4));
        UUID line = onlyLine(sale).id();
        saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        storedBeforeTheCountRule(line);
        assertThatThrownBy(() -> completeAsCf(sale.id())).isInstanceOf(ValidationException.class);

        as(shop.operator());
        FulfillmentTaskResponseDTO task = fulfillmentService.get(line);
        assertThat(task.requestedCurls()).isTrue();
        assertThat(task.requestedCurlCount()).isNull();
        assertThat(onlySource(line, source.getId()).acceptsOutputCurlCount()).isTrue();
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), 0)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("al menos 1");

        task = fulfillmentService.transform(line, new FulfillTransformationRequestDTO(source.getId(), 3));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(curlsOfProduct(productOfItem(task.itemId()))).isEqualTo("3");
        assertThat(requestedCurlsOf(line)).containsEntry("requested_curls", true)
                .containsEntry("requested_curl_count", null);

        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(sale.id());
        assertThat(completed.details()).singleElement().satisfies(detail -> {
            assertThat(detail.id()).isEqualTo(line);
            assertThat(detail.price()).isEqualByComparingTo(PRICE);
            assertThat(detail.invoiceDescription()).isEqualTo(target.getName());
            assertThat(detail.requestedCurls()).isTrue();
            assertThat(detail.requestedCurlCount()).isNull();
        });
        assertThat(completed.total()).isEqualByComparingTo(PRICE);
    }

    /**
     * C'. La misma linea historica, y el Operador no registra nada: no hay error
     * por omitirla y no se inventa ninguna; la pieza sale como se pidio.
     */
    @Test
    void aStoredYesWithoutCountIsFabricatedWithoutInventingOne() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 200, 3), 1).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(
                openUnresolvedSale("Sin cantidad").id(), curlRequest(target, true, 2));
        UUID line = onlyLine(sale).id();
        saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        storedBeforeTheCountRule(line);

        as(shop.operator());
        FulfillmentTaskResponseDTO done = fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), null));

        assertThat(productOfItem(done.itemId())).isEqualTo(target.getId());
        assertThat(curlsOfProduct(target.getId())).isNull();
        assertThat(requestedCurlsOf(line)).containsEntry("requested_curls", true);
    }

    /**
     * A, B, C, H, O. "Si" exige cuantos —un entero de 1 en adelante— por los dos
     * caminos (pieza del catalogo y pieza nueva), y un rechazo no deja nada: ni
     * linea, ni unidad apartada, ni producto nuevo en el catalogo.
     */
    @Test
    void aYesWithoutAValidCountIsRejectedOnBothPathsAndLeavesNothing() {
        Shop shop = shop();
        Family hoja = seededFamily("HR");
        Product stockedLeaf = leafWithoutCurls(shop, 112);
        InventoryItem unit = stocked(shop, stockedLeaf, 1).get(0);
        Map<String, Object> wanted = attributes("largo", 93, "tieneOrificio", false);
        long productsBefore = products();

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Cuantos").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(stockedLeaf, true, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Indique cuántos colochos lleva la pieza");
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(stockedLeaf, true, 0)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("de 1 en adelante");
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(stockedLeaf, true, -2)))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> saleService.requestSpecification(saleId, new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), wanted, 1, PRICE, null, null, true, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Indique cuántos colochos lleva la pieza");
        for (Object impossible : List.of(3.5, -2)) {
            Map<String, Object> bad = new HashMap<>(wanted);
            bad.put("cantidadColochos", impossible);
            assertThatThrownBy(() -> saleService.requestSpecification(saleId, new RequestSpecificationDTO(
                    hoja.getId(), shop.model().getId(), bad, 1, PRICE, null, null, true, null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("entero");
        }
        Map<String, Object> zero = new HashMap<>(wanted);
        zero.put("cantidadColochos", 0);
        assertThatThrownBy(() -> saleService.requestSpecification(saleId, new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), zero, 1, PRICE, null, null, true, null)))
                .isInstanceOf(ValidationException.class);

        assertThat(linesOfSale(saleId)).isZero();
        assertThat(itemStatus(unit.getId())).isEqualTo("AVAILABLE");
        assertThat(stockOf(stockedLeaf, shop)).isEqualTo(1);
        assertThat(products()).isEqualTo(productsBefore);

        // Con la cantidad, los dos caminos aceptan.
        SaleResponseDTO sale = saleService.addDetail(saleId, curlRequest(stockedLeaf, true, 4));
        Map<String, Object> withCount = new HashMap<>(wanted);
        withCount.put("cantidadColochos", 4);
        sale = saleService.requestSpecification(saleId, new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), withCount, 1, PRICE, null, null, true, null));
        assertThat(sale.details()).extracting(SaleDetailResponseDTO::requestedCurlCount).containsExactly(4, 4);
        assertThat(sale.details()).extracting(SaleDetailResponseDTO::requestedCurls).containsExactly(true, true);
    }

    /** F. "No" se acepta sin cantidad o con su cero, y queda guardado como "no". */
    @Test
    void aNoIsAcceptedWithoutCountOrWithItsZero() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 113);
        emptyInventory(shop, target);

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Sin colochos, cero");
        sale = saleService.addDetail(sale.id(), curlRequest(target, false, null));
        sale = saleService.addDetail(sale.id(), curlRequest(target, false, 0));
        UUID saleId = sale.id();
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(target, false, 3)))
                .isInstanceOf(ValidationException.class);

        assertThat(sale.details()).extracting(SaleDetailResponseDTO::requestedCurls).containsExactly(false, false);
        assertThat(sale.details()).extracting(SaleDetailResponseDTO::requestedCurlCount).containsOnlyNulls();
        assertThat(linesOfSale(saleId)).isEqualTo(2);
    }

    /**
     * I. Tres unidades con "si, 4" y una sola terminada: una sale de stock y
     * dos se fabrican, y las tres conservan los 4 pedidos. Se cobran tres
     * lineas, una vez cada una.
     */
    @Test
    void everyUnitKeepsTheSellersCountWithPartialStock() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 114);
        stocked(shop, target, 1);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(
                openUnresolvedSale("Tres de cuatro").id(),
                new AddSaleDetailRequestDTO(target.getId(), null, null, 3, PRICE, null, null, null, true, 4));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));

        assertThat(sale.details()).hasSize(3)
                .allSatisfy(detail -> {
                    assertThat(detail.requestedCurls()).isTrue();
                    assertThat(detail.requestedCurlCount()).isEqualTo(4);
                });
        assertThat(sale.details()).extracting(detail -> detail.fulfillment().type())
                .containsExactlyInAnyOrder(
                        FulfillmentType.STOCK_RELEASE, FulfillmentType.TRANSFORMATION, FulfillmentType.TRANSFORMATION);
        assertThat(sale.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));

        as(shop.operator());
        assertThat(tasksOf(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), sale.id()))
                .hasSize(2)
                .allSatisfy(task -> assertThat(task.requestedCurlCount()).isEqualTo(4));
    }

    /**
     * D. "No lleva colochos": la linea lo guarda, el Operador no puede
     * agregarle (se rechaza sin mover nada) y la pieza sale con cero —sin
     * colochos, explicito—, en la misma linea y con el nombre pedido.
     */
    @Test
    void aNoIsKeptAndTheOperatorCannotAddCurls() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 180, 3), 1).get(0);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Sin colochos").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(target, false, 2)))
                .isInstanceOf(ValidationException.class);
        SaleResponseDTO sale = saleService.addDetail(saleId, curlRequest(target, false, null));
        UUID line = onlyLine(sale).id();
        saleService.dispatch(saleId, new DispatchSaleRequestDTO(null));

        as(shop.operator());
        FulfillmentTaskResponseDTO task = fulfillmentService.get(line);
        assertThat(task.requestedCurls()).isFalse();
        assertThat(task.requestedCurlCount()).isNull();
        assertThat(onlySource(line, source.getId()).acceptsOutputCurlCount()).isFalse();
        long transformationsBefore = transformations();
        long workResultsBefore = workResults();
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(source.getId(), 2)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no lleva colochos");
        assertUntouched(line, source, 1, transformationsBefore, workResultsBefore);

        task = fulfillmentService.transform(line, new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(task.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(curlsOfProduct(productOfItem(task.itemId()))).isEqualTo("0");
        assertThat(requestedCurlsOf(line)).containsEntry("requested_curls", false);
        as(shop.seller());
        SaleDetailResponseDTO billed = onlyLine(saleService.get(saleId));
        assertThat(billed.id()).isEqualTo(line);
        assertThat(billed.invoiceDescription()).isEqualTo(target.getName());
        assertThat(billed.requestedCurls()).isFalse();
    }

    /**
     * E. Una solicitud nueva de una pieza que no fija sus colochos no se acepta
     * sin el "si" o el "no", y no deja nada a medias; tampoco una decision donde
     * no aplica, ni una cantidad imposible.
     */
    @Test
    void aNewRequestWithoutTheYesNoChoiceIsIncomplete() {
        Shop shop = shop();
        Family hoja = seededFamily("HR");
        Product target = leafWithoutCurls(shop, 110);
        Product screw = normalProduct(shop, "TORNILLO");
        stocked(shop, screw, 1);
        Map<String, Object> wanted = attributes("largo", 91, "tieneOrificio", false);

        as(shop.seller());
        UUID saleId = openUnresolvedSale("Incompleta").id();
        assertThatThrownBy(() -> saleService.addDetail(saleId, specialRequest(target, 1, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Indique si la pieza lleva colochos");
        assertThatThrownBy(() -> saleService.requestSpecification(saleId, new RequestSpecificationDTO(
                hoja.getId(), shop.model().getId(), wanted, 1, PRICE, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Indique si la pieza lleva colochos");
        assertThat(productWith(hoja, shop.model(), wanted)).isZero();
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(target, true, 0)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> saleService.addDetail(saleId, curlRequest(target, true, -1)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> saleService.addDetail(saleId, new AddSaleDetailRequestDTO(
                screw.getId(), null, null, 1, null, null, null, null, true, null)))
                .isInstanceOf(ValidationException.class);
        assertThat(linesOfSale(saleId)).isZero();
    }

    /**
     * F. Una linea anterior a la regla (sin decision guardada) se sigue leyendo:
     * "no se sabe", nunca "no lleva" ni cero, y el Operador conserva la opcion
     * de registrar la cantidad.
     */
    @Test
    void aLegacyLineWithoutDecisionKeepsTheCompatibleBehavior() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);
        InventoryItem source = stocked(shop, leaf(shop, 180, 2), 1).get(0);

        as(shop.seller());
        SaleResponseDTO sale = saleService.addDetail(
                openUnresolvedSale("Historica").id(), curlRequest(target, true, 3));
        UUID line = onlyLine(sale).id();
        saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        // Como la dejo la V42: sin las columnas de la V43.
        jdbc.update("UPDATE sale_detail SET requested_curls = NULL, requested_curl_count = NULL WHERE id = ?", line);

        as(shop.operator());
        FulfillmentTaskResponseDTO task = fulfillmentService.get(line);
        assertThat(task.requestedCurls()).isNull();
        assertThat(task.requestedCurlCount()).isNull();
        assertThat(task.curlCountApplies()).isTrue();
        assertThat(onlySource(line, source.getId()).acceptsOutputCurlCount()).isTrue();
        task = fulfillmentService.transform(line, new FulfillTransformationRequestDTO(source.getId(), null));
        assertThat(productOfItem(task.itemId())).isEqualTo(target.getId());
        assertThat(curlsOfProduct(target.getId())).isNull();
        as(shop.seller());
        assertThat(onlyLine(saleService.get(sale.id())).requestedCurls()).isNull();
    }

    /**
     * Dos lineas de la misma venta, cada una con su decision: la de una nunca
     * cambia la otra.
     */
    @Test
    void eachLineOfASaleKeepsItsOwnCurlDecision() {
        Shop shop = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Dos lineas");
        sale = saleService.addDetail(sale.id(), curlRequest(target, true, 4));
        sale = saleService.addDetail(sale.id(), curlRequest(target, false, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID four = sale.details().get(0).id();
        UUID none = sale.details().get(1).id();

        as(shop.operator());
        List<FulfillmentTaskResponseDTO> queue = fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null);
        assertThat(onlyTask(queue, four).requestedCurls()).isTrue();
        assertThat(onlyTask(queue, four).requestedCurlCount()).isEqualTo(4);
        assertThat(onlyTask(queue, none).requestedCurls()).isFalse();
        assertThat(onlyTask(queue, none).requestedCurlCount()).isNull();
    }

    /** J. Otro taller no puede fabricar —ni con otra cantidad— lo que pidio nuestra venta. */
    @Test
    void aNeighbourCannotOverrideOurSellersDecision() {
        Shop shop = shop();
        Shop neighbour = shop();
        Product target = leafWithoutCurls(shop, 110);
        emptyInventory(shop, target);
        InventoryItem theirs = stocked(neighbour, leaf(neighbour, 180, 2), 1).get(0);

        as(shop.seller());
        UUID line = onlyLine(saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Nuestra").id(), curlRequest(target, false, null)).id(),
                new DispatchSaleRequestDTO(null))).id();

        as(neighbour.operator());
        assertThatThrownBy(() -> fulfillmentService.transform(
                line, new FulfillTransformationRequestDTO(theirs.getId(), 3)))
                .isInstanceOf(EntityNotFoundException.class);
        as(shop.operator());
        assertThat(fulfillmentService.get(line).status()).isEqualTo(FulfillmentStatus.QUEUED);
        assertThat(requestedCurlsOf(line)).containsEntry("requested_curls", false);
        assertThat(itemStatus(theirs.getId())).isEqualTo("AVAILABLE");
    }

    @Test
    void queuePagesKeepSaleIdentityAreaProgressAndWorkshopScope() {
        Shop shop = shop();
        Product leaf = leaf(shop, 120, 2);
        stocked(shop, leaf, 3);
        as(shop.seller());
        SaleResponseDTO sale = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Mismo cliente").id(), specialRequest(leaf, 5, null)).id(),
                new DispatchSaleRequestDTO(null));
        SaleResponseDTO other = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Mismo cliente").id(), specialRequest(leaf, 1, null)).id(),
                new DispatchSaleRequestDTO(null));
        as(shop.control());
        List<FulfillmentTaskResponseDTO> control = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            List<FulfillmentTaskResponseDTO> part = fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, 1, page);
            assertThat(part).hasSize(1).allSatisfy(task -> {
                assertThat(task.saleId()).isEqualTo(sale.id());
                assertThat(task.area()).isEqualTo(FulfillmentArea.CONTROL);
                assertThat(task.quantity()).isEqualTo(1);
                assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(3, 0));
            });
            control.addAll(part);
        }
        assertThat(control).extracting(FulfillmentTaskResponseDTO::id).doesNotHaveDuplicates();
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, 1, 3)).isEmpty();
        fulfillmentService.release(control.get(0).id(), new ReleaseFulfillmentRequestDTO(null));
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, 1, 1))
                .singleElement().satisfies(task -> {
                    assertThat(task.id()).isEqualTo(control.get(2).id());
                    assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(3, 1));
                });
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, List.of(FulfillmentStatus.FULFILLED), 1, 0))
                .extracting(FulfillmentTaskResponseDTO::id).containsExactly(control.get(0).id());
        as(shop.operator());
        List<FulfillmentTaskResponseDTO> operator = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            operator.addAll(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, 1, page));
        }
        assertThat(operator).hasSize(3).extracting(FulfillmentTaskResponseDTO::id).doesNotHaveDuplicates();
        assertThat(tasksOf(operator, sale.id())).hasSize(2).allSatisfy(task -> {
            assertThat(task.area()).isEqualTo(FulfillmentArea.OPERATOR);
            assertThat(task.quantity()).isEqualTo(1);
            assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(2, 0));
        });
        assertThat(tasksOf(operator, other.id())).hasSize(1);
        as(shop.seller());
        SaleResponseDTO after = saleService.get(sale.id());
        assertThat(after.details()).extracting(SaleDetailResponseDTO::id)
                .containsExactlyInAnyOrderElementsOf(sale.details().stream().map(SaleDetailResponseDTO::id).toList());
        assertThat(after.total()).isEqualByComparingTo(sale.total());

        Shop neighbour = shop();
        as(neighbour.operator());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, 1, 0)).isEmpty();
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, 1, 1)).isEmpty();
        as(neighbour.control());
        assertThat(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, 1, 0)).isEmpty();
    }

    @Test
    void queueRejectsInvalidPageOffsets() {
        Shop shop = shop();
        as(shop.operator());
        assertThatThrownBy(() -> fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, 100, -1))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, 200, Integer.MAX_VALUE))
                .isInstanceOf(ValidationException.class);
    }

    // ── Agrupar la cola por venta ────────────────────────────────

    /**
     * Varias solicitudes del Operador en una venta: cada una es su propia linea
     * de ESA venta y todas cuentan el avance del area. Terminar una no toca las
     * demas ni otra venta, y la venta sigue sin poder cerrarse.
     */
    @Test
    void aSaleWithSeveralOperatorTasksKeepsEachLineAndCountsItsProgress() {
        Shop shop = shop();
        Product leaf = leaf(shop, 120, 2);
        emptyInventory(shop, leaf);
        Product installation = job(shop, "INSTALACION");

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Grupo");
        sale = saleService.addDetail(sale.id(), specialRequest(leaf, 2, null));
        sale = saleService.addDetail(sale.id(), new AddSaleDetailRequestDTO(
                installation.getId(), null, null, 1, new BigDecimal("150.00"), null, null));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID saleId = sale.id();
        SaleResponseDTO other = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Otra").id(), specialRequest(leaf, 1, null)).id(),
                new DispatchSaleRequestDTO(null));
        List<UUID> lines = sale.details().stream().map(SaleDetailResponseDTO::id).toList();

        as(shop.operator());
        List<FulfillmentTaskResponseDTO> mine = tasksOf(
                fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), saleId);
        assertThat(mine).extracting(FulfillmentTaskResponseDTO::id)
                .containsExactlyInAnyOrderElementsOf(lines);
        assertThat(mine).extracting(FulfillmentTaskResponseDTO::saleProgress)
                .containsOnly(new FulfillmentSaleProgressDTO(3, 0));
        assertThat(tasksOf(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), other.id()))
                .singleElement()
                .extracting(FulfillmentTaskResponseDTO::saleProgress)
                .isEqualTo(new FulfillmentSaleProgressDTO(1, 0));

        UUID service = lineOf(sale, FulfillmentType.SERVICE);
        assertThat(fulfillmentService.completeService(service).saleProgress())
                .isEqualTo(new FulfillmentSaleProgressDTO(3, 1));

        mine = tasksOf(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), saleId);
        assertThat(mine).hasSize(2)
                .allSatisfy(task -> {
                    assertThat(task.type()).isEqualTo(FulfillmentType.TRANSFORMATION);
                    assertThat(task.status()).isEqualTo(FulfillmentStatus.QUEUED);
                    assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(3, 1));
                });
        assertThat(tasksOf(fulfillmentService.getQueue(
                FulfillmentArea.OPERATOR, List.of(FulfillmentStatus.FULFILLED), null), saleId))
                .extracting(FulfillmentTaskResponseDTO::id)
                .containsExactly(service);
        assertThat(tasksOf(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), other.id()))
                .singleElement()
                .extracting(FulfillmentTaskResponseDTO::saleProgress)
                .isEqualTo(new FulfillmentSaleProgressDTO(1, 0));

        as(shop.control());
        assertThat(tasksOf(fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null), saleId)).isEmpty();
        as(shop.seller());
        assertThatThrownBy(() -> completeAsCf(saleId)).isInstanceOf(ValidationException.class);
    }

    /**
     * Liberar una de tres piezas solo cumple esa linea: las otras dos siguen
     * en la cola de Control con su unidad reservada, y la venta no se puede
     * cerrar hasta liberarlas.
     */
    @Test
    void aPartialReleaseOnlyCompletesThatLineAndTheRestStaysPending() {
        Shop shop = shop();
        Product leaf = leaf(shop, 140, 2);
        stocked(shop, leaf, 3);

        as(shop.seller());
        SaleResponseDTO sale = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Parcial").id(), specialRequest(leaf, 3, null)).id(),
                new DispatchSaleRequestDTO(null));
        UUID saleId = sale.id();

        as(shop.control());
        List<FulfillmentTaskResponseDTO> pending = tasksOf(
                fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null), saleId);
        assertThat(pending).hasSize(3)
                .allSatisfy(task -> assertThat(task.saleProgress())
                        .isEqualTo(new FulfillmentSaleProgressDTO(3, 0)));
        assertThat(pending).extracting(FulfillmentTaskResponseDTO::itemId)
                .doesNotContainNull()
                .doesNotHaveDuplicates();
        UUID first = pending.get(0).id();

        FulfillmentTaskResponseDTO released = fulfillmentService.release(
                first, new ReleaseFulfillmentRequestDTO(null));
        assertThat(released.status()).isEqualTo(FulfillmentStatus.FULFILLED);
        assertThat(released.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(3, 1));

        List<FulfillmentTaskResponseDTO> rest = tasksOf(
                fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null), saleId);
        assertThat(rest).hasSize(2)
                .noneMatch(task -> task.id().equals(first))
                .allSatisfy(task -> {
                    assertThat(task.status()).isEqualTo(FulfillmentStatus.QUEUED);
                    assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(3, 1));
                    assertThat(itemStatus(task.itemId())).isEqualTo("SOLD");
                });

        as(shop.seller());
        assertThat(saleService.get(saleId).fulfillmentStatus())
                .isEqualTo(SaleFulfillmentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> completeAsCf(saleId)).isInstanceOf(ValidationException.class);

        as(shop.control());
        rest.forEach(task -> fulfillmentService.release(task.id(), new ReleaseFulfillmentRequestDTO(null)));
        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(saleId);
        assertThat(completed.details()).hasSize(3);
        assertThat(completed.details()).extracting(detail -> detail.item().id()).doesNotHaveDuplicates();
        assertThat(completed.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));
    }

    /**
     * Tres pedidas con una terminada: Control ve UNA entrega y el Operador DOS
     * piezas por fabricar de la misma venta, nunca las tres en las dos colas.
     * La cuenta lleva tres lineas, cada una cobrada una vez.
     */
    @Test
    void aMixedSaleShowsEachAreaOnlyTheShareItOwns() {
        Shop shop = shop();
        Product leaf = leaf(shop, 150, 2);
        stocked(shop, leaf, 1);
        List<InventoryItem> sources = stocked(shop, leaf(shop, 300, 2), 2);

        as(shop.seller());
        SaleResponseDTO sale = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Mixta").id(), specialRequest(leaf, 3, null)).id(),
                new DispatchSaleRequestDTO(null));
        UUID saleId = sale.id();

        as(shop.control());
        List<FulfillmentTaskResponseDTO> control = tasksOf(
                fulfillmentService.getQueue(FulfillmentArea.CONTROL, null, null), saleId);
        assertThat(control).singleElement().satisfies(task -> {
            assertThat(task.type()).isEqualTo(FulfillmentType.STOCK_RELEASE);
            assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(1, 0));
        });
        as(shop.operator());
        List<FulfillmentTaskResponseDTO> operator = tasksOf(
                fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null), saleId);
        assertThat(operator).hasSize(2).allSatisfy(task -> {
            assertThat(task.type()).isEqualTo(FulfillmentType.TRANSFORMATION);
            assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(2, 0));
        });
        assertThat(operator).extracting(FulfillmentTaskResponseDTO::id)
                .doesNotContain(control.get(0).id());

        fulfillmentService.transform(operator.get(0).id(),
                new FulfillTransformationRequestDTO(sources.get(0).getId(), null));
        assertThat(fulfillmentService.get(operator.get(1).id()).saleProgress())
                .isEqualTo(new FulfillmentSaleProgressDTO(2, 1));
        as(shop.control());
        assertThat(fulfillmentService.get(control.get(0).id()).saleProgress())
                .isEqualTo(new FulfillmentSaleProgressDTO(1, 0));
        fulfillmentService.release(control.get(0).id(), new ReleaseFulfillmentRequestDTO(null));
        as(shop.operator());
        fulfillmentService.transform(operator.get(1).id(),
                new FulfillTransformationRequestDTO(sources.get(1).getId(), null));

        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(saleId);
        assertThat(completed.details()).hasSize(3);
        assertThat(completed.details()).extracting(detail -> detail.item().id()).doesNotHaveDuplicates();
        assertThat(completed.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));
    }

    /**
     * Un id cambiado a mano no actua sobre otra linea ni salta de area: la
     * accion se rechaza y nada se mueve.
     */
    @Test
    void craftedTaskIdsCannotActOnTheWrongLineOrArea() {
        Shop shop = shop();
        Product stockedLeaf = leaf(shop, 130, 2);
        stocked(shop, stockedLeaf, 1);
        Product missing = leaf(shop, 95, 2);
        emptyInventory(shop, missing);
        InventoryItem source = stocked(shop, leaf(shop, 190, 2), 1).get(0);
        Product screw = normalProduct(shop, "TORNILLO");
        stocked(shop, screw, 2);

        as(shop.seller());
        SaleResponseDTO sale = openUnresolvedSale("Ids");
        sale = saleService.addDetail(sale.id(), specialRequest(stockedLeaf, 1, null));
        sale = saleService.addDetail(sale.id(), specialRequest(missing, 1, null));
        sale = saleService.addDetail(sale.id(), normalRequest(screw, 1));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID controlLine = lineOf(sale, FulfillmentType.STOCK_RELEASE);
        UUID operatorLine = lineOf(sale, FulfillmentType.TRANSFORMATION);
        UUID directLine = sale.details().stream()
                .filter(detail -> detail.fulfillment() == null)
                .findFirst().orElseThrow().id();

        as(shop.control());
        assertThatThrownBy(() -> fulfillmentService.release(operatorLine, new ReleaseFulfillmentRequestDTO(null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fulfillmentService.release(directLine, new ReleaseFulfillmentRequestDTO(null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> fulfillmentService.release(UUID.randomUUID(), new ReleaseFulfillmentRequestDTO(null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> fulfillmentService.release(
                controlLine, new ReleaseFulfillmentRequestDTO(source.getId())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no es del producto solicitado");

        as(shop.operator());
        assertThatThrownBy(() -> fulfillmentService.transform(
                controlLine, new FulfillTransformationRequestDTO(source.getId(), null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fulfillmentService.completeService(operatorLine))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fulfillmentService.getSources(controlLine, "hoja"))
                .isInstanceOf(ValidationException.class);

        assertThat(fulfillmentService.get(controlLine).status()).isEqualTo(FulfillmentStatus.QUEUED);
        assertThat(fulfillmentService.get(operatorLine).status()).isEqualTo(FulfillmentStatus.QUEUED);
        assertThat(itemStatus(source.getId())).isEqualTo("AVAILABLE");
        assertThat(fulfillmentService.get(controlLine).saleProgress())
                .isEqualTo(new FulfillmentSaleProgressDTO(1, 0));
    }

    // ── Buscar la pieza de origen ────────────────────────────────

    /**
     * La busqueda va al servidor y se aplica antes del tope: encuentra una pieza
     * que no cabia en la primera pagina, sin mayusculas ni acentos, por SKU,
     * codigo corto o etiqueta. Nunca ofrece una pieza no elegible —demasiado
     * corta o reservada para otra venta— aunque coincida.
     */
    @Test
    void theSourceSearchFindsOnlyEligiblePiecesAndReachesPastTheFirstPage() {
        Shop shop = shop();
        Product target = leaf(shop, 100, 2);
        emptyInventory(shop, target);
        stocked(shop, leaf(shop, 210, 2), 200);
        String sku = "SKU-ZETA-" + unique();
        InventoryItem wanted = stocked(shop, namedLeaf(shop, "HOJA CAMIÓN ÑANDÚ", sku, 240), 1).get(0);
        InventoryItem tooShort = stocked(shop, namedLeaf(shop, "HOJA CAMIÓN CORTA", "SKU-" + unique(), 60), 1).get(0);
        Product reservedLeaf = namedLeaf(shop, "HOJA CAMIÓN APARTADA", "SKU-" + unique(), 250);
        InventoryItem reserved = stocked(shop, reservedLeaf, 1).get(0);

        as(shop.seller());
        saleService.addDetail(openUnresolvedSale("Aparta").id(), specialRequest(reservedLeaf, 1, null));
        assertThat(itemStatus(reserved.getId())).isEqualTo("SOLD");
        SaleResponseDTO sale = saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Busca").id(), specialRequest(target, 1, null)).id(),
                new DispatchSaleRequestDTO(null));
        UUID line = onlyLine(sale).id();

        as(shop.operator());
        assertThat(sourceIds(line, null)).hasSize(200).doesNotContain(wanted.getId());
        assertThat(sourceIds(line, "   ")).hasSize(200);
        assertThat(sourceIds(line, "camion")).containsExactly(wanted.getId());
        assertThat(sourceIds(line, "CaMiÓn ñandu")).containsExactly(wanted.getId());
        assertThat(sourceIds(line, sku.toLowerCase(Locale.ROOT))).containsExactly(wanted.getId());
        assertThat(sourceIds(line, wanted.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT)))
                .containsExactly(wanted.getId());
        assertThat(sourceIds(line, BarcodeIdentity.item(wanted.getId()))).containsExactly(wanted.getId());
        assertThat(sourceIds(line, BarcodeIdentity.item(tooShort.getId()))).isEmpty();
        assertThat(sourceIds(line, BarcodeIdentity.item(reserved.getId()))).isEmpty();
        assertThat(sourceIds(line, "apartada")).isEmpty();
        assertThat(sourceIds(line, "%")).isEmpty();
        assertThat(sourceIds(line, "sin coincidencias " + unique())).isEmpty();
        assertThatThrownBy(() -> fulfillmentService.getSources(line, "x".repeat(101)))
                .isInstanceOf(ValidationException.class);
    }

    /** Otro taller no encuentra nuestras piezas ni cuenta nuestras ventas. */
    @Test
    void aNeighbourWorkshopCannotFindOurPiecesOrSeeOurProgress() {
        Shop shop = shop();
        Shop neighbour = shop();
        Product target = leaf(shop, 100, 2);
        emptyInventory(shop, target);
        InventoryItem ours = stocked(shop, leaf(shop, 200, 2), 1).get(0);
        Product theirTarget = leaf(neighbour, 100, 2);
        emptyInventory(neighbour, theirTarget);

        as(shop.seller());
        UUID ourLine = onlyLine(saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Nuestra").id(), specialRequest(target, 1, null)).id(),
                new DispatchSaleRequestDTO(null))).id();
        as(neighbour.seller());
        UUID theirLine = onlyLine(saleService.dispatch(saleService.addDetail(
                openUnresolvedSale("Vecina").id(), specialRequest(theirTarget, 1, null)).id(),
                new DispatchSaleRequestDTO(null))).id();

        as(neighbour.operator());
        assertThat(sourceIds(theirLine, BarcodeIdentity.item(ours.getId()))).isEmpty();
        assertThat(sourceIds(theirLine, ours.getId().toString())).isEmpty();
        assertThatThrownBy(() -> fulfillmentService.getSources(ourLine, null))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(fulfillmentService.getQueue(FulfillmentArea.OPERATOR, null, null))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.id()).isEqualTo(theirLine);
                    assertThat(task.saleProgress()).isEqualTo(new FulfillmentSaleProgressDTO(1, 0));
                });
    }

    private Product leafWithoutCurls(Shop shop, int length) {
        return productRepository.save(Product.builder()
                .family(seededFamily("HR"))
                .model(shop.model())
                .name("HOJA SIN COLOCHOS " + length + " " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes("largo", length, "tieneOrificio", false))
                .build());
    }

    private Product namedLeaf(Shop shop, String name, String sku, int length) {
        return productRepository.save(Product.builder()
                .family(seededFamily("HR"))
                .model(shop.model())
                .name(name + " " + unique())
                .sku(sku)
                .attributes(attributes("largo", length, "tieneOrificio", false, "cantidadColochos", 2))
                .build());
    }

    private FulfillmentSourceDTO onlySource(UUID line, UUID itemId) {
        return fulfillmentService.getSources(line, null).stream()
                .filter(source -> source.item().id().equals(itemId))
                .findFirst().orElseThrow();
    }

    private List<UUID> sourceIds(UUID line, String query) {
        return fulfillmentService.getSources(line, query).stream()
                .map(source -> source.item().id())
                .toList();
    }

    private List<FulfillmentTaskResponseDTO> tasksOf(List<FulfillmentTaskResponseDTO> queue, UUID saleId) {
        return queue.stream().filter(task -> task.saleId().equals(saleId)).toList();
    }

    /** `cantidadColochos` guardada en el producto, como texto; null si no la tiene. */
    private String curlsOfProduct(UUID productId) {
        return jdbc.queryForObject(
                "SELECT attributes ->> 'cantidadColochos' FROM product WHERE id = ?", String.class, productId);
    }


    // ── Datos de partida ─────────────────────────────────────────

    private record Shop(
            Workshop workshop,
            Model model,
            Jwt seller,
            Jwt control,
            Jwt operator,
            Jwt operator2,
            Employee operatorEmployee) {
    }

    /** Un taller con una cuenta real por rol. */
    private Shop shop() {
        Workshop workshop = workshopRepository.save(Workshop.builder()
                .name("TALLER " + unique())
                .address("DIRECCION")
                .build());
        Brand brand = brandRepository.save(Brand.builder()
                .name("MARCA " + unique())
                .logoKey("brands/" + unique() + ".png")
                .build());
        Model model = modelRepository.save(Model.builder()
                .brand(brand)
                .name("MODELO " + unique())
                .yearFrom(2020)
                .yearTo(2020)
                .build());
        Employee operator = employee(workshop);
        return new Shop(
                workshop,
                model,
                token(workshop, account(employee(workshop), "SELLER")),
                token(workshop, account(employee(workshop), "CONTROL")),
                token(workshop, account(operator, "OPERATOR")),
                token(workshop, account(employee(workshop), "OPERATOR")),
                operator);
    }

    private Employee employee(Workshop workshop) {
        return employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName("APELLIDO " + unique())
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
    }

    private UserAccount account(Employee employee, String roleName) {
        Role role = roleRepository.findAll().stream()
                .filter(candidate -> roleName.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        return userAccountRepository.save(UserAccount.builder()
                .employee(employee)
                .role(role)
                .username("u_" + unique())
                .password("no-usada-en-pruebas")
                .isActive(true)
                .build());
    }

    private Jwt token(Workshop workshop, UserAccount account) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.getId().toString())
                .claim("workshop", workshop.getId().toString())
                .build();
    }

    private void as(Jwt token) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    private Family seededFamily(String code) {
        return familyRepository.findAll().stream()
                .filter(family -> code.equals(family.getCode()))
                .findFirst()
                .orElseThrow();
    }

    /** Una hoja de resorte del catalogo, del modelo del taller. */
    private Product leaf(Shop shop, int length, int curls) {
        return productRepository.save(Product.builder()
                .family(seededFamily("HR"))
                .model(shop.model())
                .name("HOJA " + length + " " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes("largo", length, "tieneOrificio", false,
                        "cantidadColochos", curls))
                .build());
    }

    /** Inventario con `units` unidades disponibles y el lote que las respalda. */
    private List<InventoryItem> stocked(Shop shop, Product product, int units) {
        return stockedAtCost(shop, product, units, COST);
    }

    /** Inventario con su costo de referencia y un lote al mismo costo. */
    private List<InventoryItem> stockedAtCost(Shop shop, Product product, int units, BigDecimal cost) {
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(shop.workshop())
                .price(PRICE)
                .cost(cost)
                .stock(units)
                .minStock(0)
                .build());
        acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(shop.workshop())
                .inventory(inventory)
                .supplier(supplier(shop.workshop()))
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(AcquisitionType.NATIONAL)
                .quantityReceived(units)
                .quantityRemaining(units)
                .unitCost(cost)
                .acquiredAt(LocalDateTime.now())
                .build());
        List<InventoryItem> items = new ArrayList<>();
        for (int unit = 0; unit < units; unit++) {
            items.add(itemRepository.save(InventoryItem.builder()
                    .inventory(inventory)
                    .status(InventoryItemStatus.AVAILABLE)
                    .build()));
        }
        return items;
    }

    private void emptyInventory(Shop shop, Product product) {
        inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(shop.workshop())
                .price(PRICE)
                .cost(COST)
                .stock(0)
                .minStock(0)
                .build());
    }

    private final Map<UUID, com.cns.spring_house.supplier.entity.Supplier> suppliers = new HashMap<>();

    private com.cns.spring_house.supplier.entity.Supplier supplier(Workshop workshop) {
        return suppliers.computeIfAbsent(workshop.getId(), ignored ->
                supplierRepository.save(com.cns.spring_house.supplier.entity.Supplier.builder()
                        .workshop(workshop)
                        .name("PROVEEDOR " + unique())
                        .build()));
    }

    /** Una pieza normal del catalogo (sin modelo ni caracteristicas). */
    private Product normalProduct(Shop shop, String name) {
        Family family = familyRepository.save(Family.builder()
                .code("N" + unique())
                .name("NORMALES " + unique())
                .requiresModel(false)
                .category(FamilyCategory.NORMAL)
                .build());
        return productRepository.save(Product.builder()
                .family(family)
                .name(name + " " + unique())
                .sku("SKU-" + unique())
                .attributes(new HashMap<>())
                .build());
    }

    /** Un servicio del catalogo del taller. */
    private Product job(Shop shop, String name) {
        Family family = familyRepository.save(Family.builder()
                .code("J" + unique())
                .name("SERVICIOS " + unique())
                .requiresModel(false)
                .category(FamilyCategory.JOB)
                .build());
        Product product = productRepository.save(Product.builder()
                .family(family)
                .name(name + " " + unique())
                .sku("SKU-" + unique())
                .attributes(new HashMap<>())
                .build());
        jobRepository.save(Job.builder()
                .product(product)
                .workshop(shop.workshop())
                .price(new BigDecimal("150.00"))
                .build());
        return product;
    }

    /** Una venta sin NIT del vehiculo {@link #PLATE}: puede pedir servicios. */
    private SaleResponseDTO openUnresolvedSale(String name) {
        return saleService.create(new CreateSaleRequestDTO(null, PLATE, TaxMode.UNRESOLVED, name));
    }

    /** Una venta de mostrador, sin vehiculo. */
    private SaleResponseDTO openSaleWithoutPlate(String name) {
        return saleService.create(new CreateSaleRequestDTO(null, null, TaxMode.UNRESOLVED, name));
    }

    /** Una pieza especial con la decision de colochos del vendedor. */
    private AddSaleDetailRequestDTO curlRequest(Product product, Boolean curls, Integer count) {
        return new AddSaleDetailRequestDTO(
                product.getId(), null, null, 1, PRICE, null, null, null, curls, count);
    }

    /**
     * Como quedo una linea guardada con "si" y sin cantidad antes de que la
     * cantidad fuera obligatoria: hoy ninguna linea nueva puede quedar asi.
     */
    private void storedBeforeTheCountRule(UUID line) {
        jdbc.update("UPDATE sale_detail SET requested_curl_count = NULL WHERE id = ?", line);
    }

    private Map<String, Object> requestedCurlsOf(UUID line) {
        return jdbc.queryForMap(
                "SELECT requested_curls, requested_curl_count FROM sale_detail WHERE id = ?", line);
    }

    /** El sobrante que dejo la fabricacion de la linea, con su producto, inventario y familia. */
    private Map<String, Object> leftoverOf(UUID line) {
        return jdbc.queryForMap(
                "SELECT item.id AS item_id, item.status, inv.id AS inventory_id, inv.workshop_id, "
                        + "p.family_id, p.attributes ->> 'cantidadColochos' AS curls, "
                        + "p.attributes ->> 'largo' AS largo "
                        + "FROM sale_detail d "
                        + "JOIN transformation_item ti ON ti.transformation_id = d.fulfillment_transformation_id "
                        + "AND ti.role = 'REMAINDER' "
                        + "JOIN inventory_item item ON item.id = ti.item_id "
                        + "JOIN inventory inv ON inv.id = item.inventory_id "
                        + "JOIN product p ON p.id = inv.product_id "
                        + "WHERE d.id = ?",
                line);
    }

    private AddSaleDetailRequestDTO specialRequest(Product product, int quantity, String instructions) {
        return new AddSaleDetailRequestDTO(
                product.getId(), null, null, quantity, PRICE, null, instructions);
    }

    private SaleResponseDTO completeAsCf(UUID saleId) {
        return saleService.completeSale(saleId, new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH, TaxMode.CF, null, null));
    }

    // ── Lecturas ─────────────────────────────────────────────────

    private SaleDetailResponseDTO onlyLine(SaleResponseDTO sale) {
        assertThat(sale.details()).hasSize(1);
        return sale.details().get(0);
    }

    private SaleDetailResponseDTO lineById(SaleResponseDTO sale, UUID id) {
        return sale.details().stream().filter(detail -> detail.id().equals(id)).findFirst().orElseThrow();
    }

    private SaleDetailResponseDTO lineByType(SaleResponseDTO sale, FulfillmentType type) {
        return sale.details().stream()
                .filter(detail -> detail.fulfillment() != null && detail.fulfillment().type() == type)
                .findFirst().orElseThrow();
    }

    private UUID lineOf(SaleResponseDTO sale, FulfillmentType type) {
        return lineByType(sale, type).id();
    }

    private FulfillmentTaskResponseDTO onlyTask(List<FulfillmentTaskResponseDTO> queue, UUID id) {
        return queue.stream().filter(task -> task.id().equals(id)).findFirst().orElseThrow();
    }

    private int stockOf(Product product, Shop shop) {
        return jdbc.queryForObject(
                "SELECT stock FROM inventory WHERE product_id = ? AND workshop_id = ?",
                Integer.class, product.getId(), shop.workshop().getId());
    }

    private String itemStatus(UUID itemId) {
        return jdbc.queryForObject("SELECT status FROM inventory_item WHERE id = ?", String.class, itemId);
    }

    private BigDecimal costTotalOf(UUID detailId) {
        return jdbc.queryForObject("SELECT cost_total FROM sale_detail WHERE id = ?", BigDecimal.class, detailId);
    }

    private UUID productOfItem(UUID itemId) {
        return jdbc.queryForObject(
                "SELECT i.product_id FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE item.id = ?", UUID.class, itemId);
    }

    private UUID familyOfProduct(UUID productId) {
        return jdbc.queryForObject("SELECT family_id FROM product WHERE id = ?", UUID.class, productId);
    }

    private UUID modelOfProduct(UUID productId) {
        return jdbc.queryForObject("SELECT model_id FROM product WHERE id = ?", UUID.class, productId);
    }

    private long inventoryCount(UUID productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE product_id = ?", Long.class, productId);
    }

    private String workResultStatusOfItem(UUID itemId) {
        return jdbc.queryForObject("SELECT status FROM work_result WHERE item_id = ?", String.class, itemId);
    }

    private long transformations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transformation", Long.class);
    }

    private long products() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class);
    }

    private long productWith(Family family, Model model, Map<String, Object> wanted) {
        return productRepository.findAll().stream()
                .filter(product -> product.getFamily().getId().equals(family.getId()))
                .filter(product -> product.getModel() != null
                        && product.getModel().getId().equals(model.getId()))
                .filter(product -> sameAttributes(product.getAttributes(), wanted))
                .count();
    }

    private boolean sameAttributes(Map<String, Object> stored, Map<String, Object> wanted) {
        if (stored == null || stored.size() != wanted.size()) {
            return false;
        }
        return wanted.entrySet().stream().allMatch(entry -> {
            Object value = stored.get(entry.getKey());
            if (value instanceof Number number && entry.getValue() instanceof Number expected) {
                return number.doubleValue() == expected.doubleValue();
            }
            return entry.getValue().equals(value);
        });
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Ejecuta las acciones a la vez, cada una con su propio contexto de
     * seguridad, y devuelve cuales prosperaron. Un fallo de negocio cuenta como
     * "no prospero"; cualquier otro error se propaga para no confundir un
     * interbloqueo con una negativa legitima.
     */
    private List<Boolean> concurrently(Jwt token, Supplier<?>... actions) throws Exception {
        List<Map.Entry<Jwt, Supplier<?>>> runs = new ArrayList<>();
        for (Supplier<?> action : actions) {
            runs.add(Map.entry(token, action));
        }
        return concurrentlyAs(runs);
    }

    /** Como {@link #concurrently}, pero cada accion con su propia cuenta. */
    private List<Boolean> concurrentlyAs(List<Map.Entry<Jwt, Supplier<?>>> runs) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(runs.size());
        ExecutorService pool = Executors.newFixedThreadPool(runs.size());
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (Map.Entry<Jwt, Supplier<?>> run : runs) {
            tasks.add(() -> {
                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(run.getKey()));
                try {
                    startTogether.await(15, TimeUnit.SECONDS);
                    run.getValue().get();
                    return true;
                } catch (ValidationException | EntityNotFoundException rejected) {
                    return false;
                } catch (RuntimeException failure) {
                    unexpected.add(failure.getClass().getName() + ": " + failure.getMessage());
                    return false;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }
        List<Future<Boolean>> futures;
        try {
            futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            outcomes.add(future.get());
        }
        assertThat(unexpected).isEmpty();
        return outcomes;
    }

}
