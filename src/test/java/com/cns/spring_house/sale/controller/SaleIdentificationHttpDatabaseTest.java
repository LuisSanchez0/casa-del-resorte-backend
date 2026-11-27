package com.cns.spring_house.sale.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.cns.spring_house.acquisition.entity.AcquisitionOrigin;
import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.acquisition.entity.InventoryAcquisition;
import com.cns.spring_house.acquisition.repository.InventoryAcquisitionRepository;
import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
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
import com.cns.spring_house.job.repository.JobRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CancelSaleRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.service.SaleService;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.SeenCatalog;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * "Cliente y vehiculo" de una venta abierta, por HTTP de verdad (filtro JWT,
 * `@PreAuthorize`, controlador, servicio con su transaccion y PostgreSQL).
 *
 * <ul>
 * <li>Escenario A: una venta "Sin NIT" sin nombre ni placa, con piezas
 * especiales, no se envia; se le guarda el nombre SIN recrearla y entonces se
 * envia, con la misma venta, las mismas lineas, precios, reservas y total.</li>
 * <li>El nombre que cuenta para enviar es el PERSISTIDO: en blanco no
 * desbloquea, y un nombre distinto en el envio no pisa el guardado (409).</li>
 * <li>Precondiciones `expected*` (409), no dejar en blanco un nombre, NIT sin
 * nombre suelto, longitudes.</li>
 * <li>La placa es opcional, tambien para servicios; si la venta la tiene, la
 * ven las dos colas del Operador y el detalle de cada pedido, de servicio y de
 * pieza; ni otra venta ni otro taller la ven.</li>
 * <li>Placa: se agrega o corrige antes de enviar; despues solo se agrega si
 * faltaba, y el taller lee la vigente (no hay copia por pedido).</li>
 * <li>Permisos: solo SELLER del mismo taller (403 a los demas roles, 401 sin
 * token, 404 a otro taller); una venta cerrada no se edita.</li>
 * <li>Carreras: dos guardados a la vez (uno gana, otro 409) y un envio que
 * espera al guardado y valida lo confirmado (o lo deshecho).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SaleIdentificationHttpDatabaseTest {

    private static final String SALES = "/v1/sales";
    private static final String FULFILLMENTS = "/v1/fulfillments";
    private static final BigDecimal PRICE = new BigDecimal("500.00");
    private static final BigDecimal COST = new BigDecimal("100.00");
    private static final String NAME_MISSING =
            "Indique el nombre del cliente antes de enviar la comanda";

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SaleService saleService;

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

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision("sale_identification_http_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
    }

    // ── Escenario A: el defecto reportado ────────────────────────

    @Test
    void aNamelessSaleGetsItsNameWithoutBeingRecreatedAndOnlyThenIsSent() throws Exception {
        Shop shop = shop();
        Product fromStock = leaf(shop, 120, 2);
        List<UUID> units = stocked(shop, fromStock, 1);
        Product toFabricate = leaf(shop, 130, 2);
        emptyInventory(shop, toFabricate);
        Product alsoFromStock = leaf(shop, 140, 3);
        stocked(shop, alsoFromStock, 1);

        // 1. "Sin NIT", sin nombre ni placa.
        JsonNode created = body(perform(post(SALES).with(shop.seller().auth()),
                Map.of("taxMode", "UNRESOLVED"), 201));
        UUID saleId = UUID.fromString(created.get("id").asString());
        assertThat(created.get("customerName").isNull()).isTrue();
        assertThat(created.get("plate").isNull()).isTrue();

        // 2. Tres piezas especiales: dos reservadas de stock y una por fabricar.
        as(shop.seller());
        addSeen(saleId, special(fromStock));
        addSeen(saleId, special(toFabricate));
        SaleResponseDTO withLines = addSeen(saleId, special(alsoFromStock));
        SecurityContextHolder.clearContext();
        assertThat(withLines.details()).hasSize(3);
        assertThat(withLines.total()).isEqualByComparingTo("1500.00");
        Map<String, Object> before = stateOf(saleId);

        // 3. Enviar sin nombre: bloqueado, nada cambia.
        assertThat(detail(perform(post(SALES + "/" + saleId + "/dispatch").with(shop.seller().auth()),
                null, 400))).contains(NAME_MISSING);
        assertThat(stateOf(saleId)).isEqualTo(before);

        // 4. Se guarda el nombre en la MISMA venta.
        JsonNode named = body(perform(identify(saleId, shop.seller()),
                identification("  juan pérez ", null, null, null), 200));
        assertThat(named.get("id").asString()).isEqualTo(saleId.toString());
        assertThat(named.get("customerName").asString()).isEqualTo("JUAN PÉREZ");
        assertThat(named.get("taxMode").asString()).isEqualTo("UNRESOLVED");
        assertThat(named.get("total").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(named.get("details")).hasSize(3);
        assertThat(stateApartFromIdentification(saleId)).isEqualTo(withoutIdentification(before));

        // 5. Releer (recargar o volver a elegir la venta) conserva el nombre.
        JsonNode reread = body(perform(get(SALES + "/" + saleId).with(shop.seller().auth()), null, 200));
        assertThat(reread.get("customerName").asString()).isEqualTo("JUAN PÉREZ");

        // 6. Ahora si se envia, y el Operador ve el nombre guardado.
        JsonNode sent = body(perform(post(SALES + "/" + saleId + "/dispatch").with(shop.seller().auth()),
                null, 200));
        assertThat(sent.get("details")).allSatisfy(line ->
                assertThat(line.get("fulfillment").get("status").asString()).isEqualTo("QUEUED"));
        List<JsonNode> operatorTasks = tasksOf(saleId, queue(shop.operator(), "operator"));
        List<JsonNode> releaseTasks = tasksOf(saleId, queue(shop.operator(), "control"));
        assertThat(operatorTasks).hasSize(1);
        assertThat(releaseTasks).hasSize(2);
        assertThat(concat(operatorTasks, releaseTasks)).allSatisfy(task -> {
            assertThat(task.get("customerName").asString()).isEqualTo("JUAN PÉREZ");
            assertThat(task.get("plate").isNull()).isTrue();
        });

        // 7. Misma venta, mismas lineas, precios, cantidades, reservas y facturacion.
        Map<String, Object> after = castMap(stateOf(saleId).get("sale"));
        assertThat(after.get("id")).isEqualTo(saleId);
        assertThat(after.get("tax_mode")).isEqualTo("UNRESOLVED");
        assertThat(after.get("customer_id")).isNull();
        assertThat(after.get("customer_nit")).isNull();
        assertThat((BigDecimal) after.get("total")).isEqualByComparingTo("1500.00");
        assertThat(lineIdentities(saleId)).isEqualTo(lineIdentities(before));
        assertThat(itemStatus(units.get(0))).isEqualTo(itemStatusIn(before, units.get(0)));
    }

    // ── El nombre que cuenta es el persistido ────────────────────

    @Test
    void whitespaceNeverCountsAsANameAndNeverUnlocksTheDispatch() throws Exception {
        Shop shop = shop();
        UUID saleId = namelessSaleWithAPiece(shop);
        Map<String, Object> before = stateOf(saleId);

        JsonNode unchanged = body(perform(identify(saleId, shop.seller()),
                identification("     ", null, null, null), 200));
        assertThat(unchanged.get("customerName").isNull()).isTrue();

        Map<String, Object> blankName = new HashMap<>();
        blankName.put("customerName", "   ");
        assertThat(detail(perform(post(SALES + "/" + saleId + "/dispatch").with(shop.seller().auth()),
                blankName, 400))).contains(NAME_MISSING);
        assertThat(stateApartFromIdentification(saleId)).isEqualTo(withoutIdentification(before));
        assertThat(saleRow(saleId).get("customer_name")).isNull();
    }

    @Test
    void aSavedNameIsCorrectedButNeverBlankedNorOverwrittenBlindly() throws Exception {
        Shop shop = shop();
        UUID saleId = namedSaleWithAPiece(shop, "Ana");

        // Corregir con lo que se vio (normalizado como el alta).
        body(perform(identify(saleId, shop.seller()),
                identification("Ana María", "ana", null, null), 200));
        assertThat(saleRow(saleId).get("customer_name")).isEqualTo("ANA MARÍA");
        Map<String, Object> before = stateOf(saleId);

        // Otra sesion ya lo habia cambiado: 409 y nada se guarda.
        perform(identify(saleId, shop.seller()), identification("Beto", "ANA", null, null), 409);
        // Sin decir lo que vio, tampoco: no hay escritura a ciegas.
        perform(identify(saleId, shop.seller()), identification("Beto", null, null, null), 409);
        // En blanco no se deja.
        assertThat(detail(perform(identify(saleId, shop.seller()),
                identification("   ", "ANA MARÍA", null, null), 400))).contains("no puede quedar en blanco");
        // El envio con otro nombre no pisa el guardado.
        Map<String, Object> otherName = new HashMap<>();
        otherName.put("customerName", "Otro");
        perform(post(SALES + "/" + saleId + "/dispatch").with(shop.seller().auth()), otherName, 409);
        assertThat(stateOf(saleId)).isEqualTo(before);

        // El mismo nombre en el envio vale (cliente anterior de la caja).
        Map<String, Object> sameName = new HashMap<>();
        sameName.put("customerName", "ana maría");
        perform(post(SALES + "/" + saleId + "/dispatch").with(shop.seller().auth()), sameName, 200);

        // Ya enviada, el nombre se corrige y el taller lee el vigente.
        body(perform(identify(saleId, shop.seller()),
                identification("Ana María López", "ANA MARÍA", null, null), 200));
        assertThat(tasksOf(saleId, queue(shop.operator(), "control")))
                .singleElement()
                .satisfies(task -> assertThat(task.get("customerName").asString()).isEqualTo("ANA MARÍA LÓPEZ"));
    }

    @Test
    void aSaleWithNitKeepsTheRegisteredCustomersNameAndLimitsAreEnforced() throws Exception {
        Shop shop = shop();
        UUID customer = customer();
        JsonNode created = body(perform(post(SALES).with(shop.seller().auth()),
                Map.of("taxMode", "NIT", "customer", customer.toString()), 201));
        UUID nitSale = UUID.fromString(created.get("id").asString());

        assertThat(detail(perform(identify(nitSale, shop.seller()),
                identification("Otro nombre", null, null, null), 400)))
                .contains("usa el nombre del cliente registrado");
        // La placa si se le puede dar.
        JsonNode plated = body(perform(identify(nitSale, shop.seller()),
                identification(null, null, "p321xyz", null), 200));
        assertThat(plated.get("plate").asString()).isEqualTo("P321XYZ");
        assertThat(plated.get("taxMode").asString()).isEqualTo("NIT");
        assertThat(saleRow(nitSale).get("customer_name")).isNull();

        UUID saleId = namelessSaleWithAPiece(shop);
        perform(identify(saleId, shop.seller()), identification("N".repeat(76), null, null, null), 400);
        perform(identify(saleId, shop.seller()), identification("Nombre", null, "P12345678901", null), 400);
        assertThat(saleRow(saleId).get("customer_name")).isNull();
        assertThat(saleRow(saleId).get("plate")).isNull();
    }

    // ── La placa: opcional y visible en cada comanda ─────────────

    @Test
    void aSuppliedPlateReachesEveryWorkshopViewOfTheSaleAndNoOther() throws Exception {
        Shop shop = shop();
        Shop neighbour = shop();
        Product fromStock = leaf(shop, 150, 2);
        stocked(shop, fromStock, 1);
        Product toFabricate = leaf(shop, 160, 2);
        emptyInventory(shop, toFabricate);
        Product service = job(shop, "CAMBIO DE BUJES");
        Product theirService = job(neighbour, "REVISION");

        // Venta B: nombre y placa; pieza de stock, pieza a fabricar y servicio.
        UUID plated = UUID.fromString(body(perform(post(SALES).with(shop.seller().auth()),
                Map.of("taxMode", "UNRESOLVED", "customerName", "Cliente B", "plate", " p123abc "), 201))
                .get("id").asString());
        as(shop.seller());
        addSeen(plated, special(fromStock));
        addSeen(plated, special(toFabricate));
        addSeen(plated, serviceLine(service));
        // Venta C: nombre, SIN placa, con un servicio: nada lo rechaza.
        UUID plateless = saleService.create(new com.cns.spring_house.sale.dto.CreateSaleRequestDTO(
                null, null, TaxMode.UNRESOLVED, "Cliente C")).id();
        addSeen(plateless, serviceLine(service));
        SecurityContextHolder.clearContext();
        perform(post(SALES + "/" + plated + "/dispatch").with(shop.seller().auth()), null, 200);
        JsonNode platelessSent = body(perform(post(SALES + "/" + plateless + "/dispatch")
                .with(shop.seller().auth()), null, 200));
        assertThat(platelessSent.get("plate").isNull()).isTrue();

        // Otro taller, con su propia placa.
        as(neighbour.seller());
        UUID theirs = saleService.create(new com.cns.spring_house.sale.dto.CreateSaleRequestDTO(
                null, "H999ZZZ", TaxMode.UNRESOLVED, "Ajeno")).id();
        UUID theirLine = addSeen(theirs, serviceLine(theirService)).details().get(0).id();
        SecurityContextHolder.clearContext();
        perform(post(SALES + "/" + theirs + "/dispatch").with(neighbour.seller().auth()), null, 200);

        // "Pedidos del taller": fabricacion y servicio de B con su placa; C sin placa.
        JsonNode workshop = queue(shop.operator(), "operator");
        List<JsonNode> bWork = tasksOf(plated, workshop);
        assertThat(bWork).extracting(task -> task.get("type").asString())
                .containsExactlyInAnyOrder("TRANSFORMATION", "SERVICE");
        assertThat(bWork).allSatisfy(task -> assertThat(task.get("plate").asString()).isEqualTo("P123ABC"));
        assertThat(tasksOf(plateless, workshop)).singleElement()
                .satisfies(task -> assertThat(task.get("plate").isNull()).isTrue());
        // "Pedidos por liberar": la pieza de stock de B con su placa.
        List<JsonNode> bRelease = tasksOf(plated, queue(shop.operator(), "control"));
        assertThat(bRelease).singleElement()
                .satisfies(task -> assertThat(task.get("plate").asString()).isEqualTo("P123ABC"));
        // El detalle de cada pedido (lo que abre "Liberar" / "Fabricar").
        for (JsonNode task : concat(bWork, bRelease)) {
            JsonNode one = body(perform(get(FULFILLMENTS + "/" + task.get("id").asString())
                    .with(shop.operator().auth()), null, 200));
            assertThat(one.get("plate").asString()).isEqualTo("P123ABC");
            assertThat(one.get("saleId").asString()).isEqualTo(plated.toString());
        }
        // Ninguna placa ajena en este taller, ni la nuestra en el otro.
        assertThat(allPlates(workshop)).doesNotContain("H999ZZZ");
        assertThat(allPlates(queue(neighbour.operator(), "operator")))
                .containsExactly("H999ZZZ");
        perform(get(FULFILLMENTS + "/" + theirLine).with(shop.operator().auth()), null, 404);
        perform(get(FULFILLMENTS + "/" + bWork.get(0).get("id").asString())
                .with(neighbour.operator().auth()), null, 404);
    }

    @Test
    void thePlateIsCorrectedBeforeDispatchAndOnlyAddedAfterIt() throws Exception {
        Shop shop = shop();
        UUID saleId = namedSaleWithAPiece(shop, "Placas");

        body(perform(identify(saleId, shop.seller()), identification("Placas", "PLACAS", "p100aaa", null), 200));
        body(perform(identify(saleId, shop.seller()), identification("Placas", "PLACAS", "P100AAB", "P100AAA"), 200));
        body(perform(identify(saleId, shop.seller()), identification("Placas", "PLACAS", null, "P100AAB"), 200));
        assertThat(saleRow(saleId).get("plate")).isNull();
        body(perform(identify(saleId, shop.seller()), identification("Placas", "PLACAS", "P100AAA", null), 200));
        perform(post(SALES + "/" + saleId + "/dispatch").with(shop.seller().auth()), null, 200);
        Map<String, Object> sent = stateOf(saleId);

        // Enviada: el taller ya puede estar trabajando con esa placa.
        assertThat(detail(perform(identify(saleId, shop.seller()),
                identification("Placas", "PLACAS", "P999XXX", "P100AAA"), 400))).contains("P100AAA");
        perform(identify(saleId, shop.seller()), identification("Placas", "PLACAS", null, "P100AAA"), 400);
        assertThat(stateOf(saleId)).isEqualTo(sent);

        // Una venta enviada SIN placa si la recibe, y el taller la lee al momento.
        UUID late = namedSaleWithAPiece(shop, "Tarde");
        perform(post(SALES + "/" + late + "/dispatch").with(shop.seller().auth()), null, 200);
        assertThat(tasksOf(late, queue(shop.operator(), "control"))).singleElement()
                .satisfies(task -> assertThat(task.get("plate").isNull()).isTrue());
        body(perform(identify(late, shop.seller()), identification("Tarde", "TARDE", "p555ccc", null), 200));
        assertThat(tasksOf(late, queue(shop.operator(), "control"))).singleElement()
                .satisfies(task -> assertThat(task.get("plate").asString()).isEqualTo("P555CCC"));
        assertThat(tasksOf(saleId, queue(shop.operator(), "control"))).singleElement()
                .satisfies(task -> assertThat(task.get("plate").asString()).isEqualTo("P100AAA"));
    }

    // ── Permisos y ciclo de vida ─────────────────────────────────

    @Test
    void onlyASellerOfTheSameWorkshopChangesTheIdentification() throws Exception {
        Shop shop = shop();
        Shop neighbour = shop();
        UUID saleId = namelessSaleWithAPiece(shop);
        Map<String, Object> before = stateOf(saleId);
        Map<String, Object> body = identification("Intruso", null, "X000XXX", null);

        for (Account other : List.of(shop.control(), shop.operator(), shop.admin(),
                account(shop.workshop(), "POLICE"), account(shop.workshop(), "SECRETARY"))) {
            perform(identify(saleId, other), body, 403);
        }
        TestSecurityContextHolder.clearContext();
        SecurityContextHolder.clearContext();
        perform(post(SALES + "/" + saleId + "/update-identification"), body, 401);
        perform(identify(saleId, neighbour.seller()), body, 404);
        assertThat(stateOf(saleId)).isEqualTo(before);
    }

    @Test
    void aClosedSaleKeepsItsIdentification() throws Exception {
        Shop shop = shop();
        Product screw = normalProduct("TORNILLO");
        stocked(shop, screw, 4);

        as(shop.seller());
        UUID completed = saleService.create(new com.cns.spring_house.sale.dto.CreateSaleRequestDTO(
                null, null, TaxMode.UNRESOLVED, "Emitida")).id();
        addSeen(completed, new AddSaleDetailRequestDTO(screw.getId(), null, null, 1, null, null, null));
        saleService.completeSale(completed, new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH, TaxMode.CF, null, null));
        UUID cancelled = saleService.create(new com.cns.spring_house.sale.dto.CreateSaleRequestDTO(
                null, null, TaxMode.UNRESOLVED, "Anulada")).id();
        addSeen(cancelled, new AddSaleDetailRequestDTO(screw.getId(), null, null, 1, null, null, null));
        saleService.cancel(cancelled, new CancelSaleRequestDTO("Cliente desistio"));
        SecurityContextHolder.clearContext();

        for (UUID closed : List.of(completed, cancelled)) {
            Map<String, Object> before = stateOf(closed);
            String name = (String) saleRow(closed).get("customer_name");
            assertThat(detail(perform(identify(closed, shop.seller()),
                    identification("Cambio", name, "P1", null), 400)))
                    .isEqualTo("La venta no se encuentra abierta");
            assertThat(stateOf(closed)).isEqualTo(before);
        }
    }

    // ── Carreras ─────────────────────────────────────────────────

    /** Dos cajas guardan a la vez sobre lo mismo que vieron: una gana, la otra 409. */
    @Test
    void twoSimultaneousSavesNeverLoseAnUpdate() throws Exception {
        Shop shop = shop();
        UUID saleId = namelessSaleWithAPiece(shop);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockSaleRow(holder, saleId);
            Future<MvcResult> ana = pool.submit(() -> http.perform(json(identify(saleId, shop.seller()),
                    identification("Ana", null, "P1AAA", null))).andReturn());
            Future<MvcResult> beto = pool.submit(() -> http.perform(json(identify(saleId, shop.seller()),
                    identification("Beto", null, null, null))).andReturn());
            awaitWaitingOnLocks(2);
            holder.commit();

            int anaStatus = ana.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            int betoStatus = beto.get(15, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(List.of(anaStatus, betoStatus)).containsExactlyInAnyOrder(200, 409);
            Map<String, Object> row = saleRow(saleId);
            if (anaStatus == 200) {
                assertThat(row.get("customer_name")).isEqualTo("ANA");
                assertThat(row.get("plate")).isEqualTo("P1AAA");
            } else {
                assertThat(row.get("customer_name")).isEqualTo("BETO");
                assertThat(row.get("plate")).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * "Enviar comanda" mientras otra caja guarda el nombre: el envio espera el
     * cerrojo de la venta y valida lo CONFIRMADO. Si el guardado se deshace, el
     * envio no sale con un nombre que nunca existio.
     */
    @Test
    void aDispatchRacingANameSaveValidatesWhatWasCommitted() throws Exception {
        Shop shop = shop();
        UUID committed = namelessSaleWithAPiece(shop);
        UUID rolledBack = namelessSaleWithAPiece(shop);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                lockSaleRow(holder, committed);
                Future<MvcResult> dispatch = pool.submit(() -> http.perform(
                        post(SALES + "/" + committed + "/dispatch").with(shop.seller().auth())).andReturn());
                awaitWaitingOnLocks(1);
                setNameInsideLock(holder, committed, "GUARDADO A TIEMPO");
                holder.commit();
                MvcResult result = dispatch.get(15, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                assertThat(body(result).get("customerName").asString()).isEqualTo("GUARDADO A TIEMPO");
            }
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                lockSaleRow(holder, rolledBack);
                Future<MvcResult> dispatch = pool.submit(() -> http.perform(
                        post(SALES + "/" + rolledBack + "/dispatch").with(shop.seller().auth())).andReturn());
                awaitWaitingOnLocks(1);
                setNameInsideLock(holder, rolledBack, "NUNCA CONFIRMADO");
                holder.rollback();
                MvcResult result = dispatch.get(15, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(400);
                assertThat(detail(result)).contains(NAME_MISSING);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList(
                "SELECT fulfillment_status FROM sale_detail WHERE sale_id = ?", String.class, rolledBack))
                .containsOnly("PENDING");
        assertThat(saleRow(rolledBack).get("customer_name")).isNull();
    }

    // ── HTTP ─────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder identify(UUID saleId, Account account) {
        return post(SALES + "/" + saleId + "/update-identification").with(account.auth());
    }

    private static Map<String, Object> identification(
            String name, String expectedName, String plate, String expectedPlate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", name);
        body.put("expectedCustomerName", expectedName);
        body.put("plate", plate);
        body.put("expectedPlate", expectedPlate);
        return body;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        MvcResult result = http.perform(json(request, body)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(status);
        return result;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String detail(MvcResult result) throws Exception {
        return body(result).get("detail").asString();
    }

    private JsonNode queue(Account account, String area) throws Exception {
        return body(perform(get(FULFILLMENTS + "/" + area).with(account.auth()), null, 200));
    }

    private static List<JsonNode> tasksOf(UUID saleId, JsonNode queue) {
        List<JsonNode> tasks = new ArrayList<>();
        queue.forEach(task -> {
            if (task.get("saleId").asString().equals(saleId.toString())) {
                tasks.add(task);
            }
        });
        return tasks;
    }

    private static List<String> allPlates(JsonNode queue) {
        List<String> plates = new ArrayList<>();
        queue.forEach(task -> {
            if (!task.get("plate").isNull()) {
                plates.add(task.get("plate").asString());
            }
        });
        return plates;
    }

    private static List<JsonNode> concat(List<JsonNode> first, List<JsonNode> second) {
        List<JsonNode> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    // ── Estado en la base ────────────────────────────────────────

    /** Todo lo que el cambio podria tocar: venta, lineas, inventario y unidades. */
    private Map<String, Object> stateOf(UUID saleId) {
        UUID workshop = jdbc.queryForObject("SELECT workshop_id FROM sale WHERE id = ?", UUID.class, saleId);
        Map<String, Object> state = new HashMap<>();
        state.put("sale", saleRow(saleId));
        state.put("lines", jdbc.queryForList("SELECT * FROM sale_detail WHERE sale_id = ? ORDER BY id", saleId));
        state.put("inventory", jdbc.queryForList(
                "SELECT * FROM inventory WHERE workshop_id = ? ORDER BY id", workshop));
        state.put("items", jdbc.queryForList(
                "SELECT item.* FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE i.workshop_id = ? ORDER BY item.id", workshop));
        state.put("lots", jdbc.queryForList(
                "SELECT * FROM inventory_acquisition WHERE workshop_id = ? ORDER BY id", workshop));
        return state;
    }

    private Map<String, Object> stateApartFromIdentification(UUID saleId) {
        return withoutIdentification(stateOf(saleId));
    }

    /** Lo mismo, sin lo que "Cliente y vehiculo" SI cambia. */
    private static Map<String, Object> withoutIdentification(Map<String, Object> state) {
        Map<String, Object> copy = new HashMap<>(state);
        Map<String, Object> sale = new HashMap<>(castMap(state.get("sale")));
        sale.remove("customer_name");
        sale.remove("plate");
        sale.remove("updated_at");
        copy.put("sale", sale);
        return copy;
    }

    private Map<String, Object> saleRow(UUID saleId) {
        return jdbc.queryForMap("SELECT * FROM sale WHERE id = ?", saleId);
    }

    /** Id, producto, cantidad, precio y unidad de cada linea. */
    private List<List<Object>> lineIdentities(UUID saleId) {
        return lineIdentities(stateOf(saleId));
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> lineIdentities(Map<String, Object> state) {
        List<List<Object>> identities = new ArrayList<>();
        for (Map<String, Object> line : (List<Map<String, Object>>) state.get("lines")) {
            identities.add(java.util.Arrays.asList(line.get("id"), line.get("product_id"),
                    line.get("quantity"), line.get("price"), line.get("item_id")));
        }
        return identities;
    }

    private String itemStatus(UUID item) {
        return jdbc.queryForObject("SELECT status FROM inventory_item WHERE id = ?", String.class, item);
    }

    @SuppressWarnings("unchecked")
    private static Object itemStatusIn(Map<String, Object> state, UUID item) {
        return ((List<Map<String, Object>>) state.get("items")).stream()
                .filter(row -> item.equals(row.get("id")))
                .map(row -> row.get("status"))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static void lockSaleRow(Connection holder, UUID saleId) throws Exception {
        try (PreparedStatement statement = holder.prepareStatement("SELECT id FROM sale WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, saleId);
            statement.executeQuery().close();
        }
    }

    private static void setNameInsideLock(Connection holder, UUID saleId, String name) throws Exception {
        try (PreparedStatement statement = holder.prepareStatement(
                "UPDATE sale SET customer_name = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setObject(2, saleId);
            statement.executeUpdate();
        }
    }

    private void awaitWaitingOnLocks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock'",
                    Long.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("Las peticiones nunca llegaron a esperar el cerrojo de la venta");
    }

    // ── Datos ────────────────────────────────────────────────────

    private record Account(UUID accountId, UUID workshopId, String role) {

        RequestPostProcessor auth() {
            return jwt().jwt(builder -> builder.subject(accountId.toString())
                            .claim("workshop", workshopId.toString())
                            .claim("role", role))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role));
        }
    }

    private record Shop(
            Workshop workshop,
            Model model,
            Account seller,
            Account control,
            Account operator,
            Account admin) {
    }

    /** Para sembrar con el servicio del vendedor, como lo hace la caja. */
    private void as(Account account) {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(account.accountId().toString())
                .claim("workshop", account.workshopId().toString())
                .claim("role", account.role())
                .build();
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token, roles.convert(token)));
    }

    /** Venta "Sin NIT" sin nombre ni placa, con una pieza especial reservada sin enviar. */
    private UUID namelessSaleWithAPiece(Shop shop) {
        return saleWithAPiece(shop, null);
    }

    private UUID namedSaleWithAPiece(Shop shop, String name) {
        return saleWithAPiece(shop, name);
    }

    private UUID saleWithAPiece(Shop shop, String name) {
        Product leaf = leaf(shop, 110, 2);
        stocked(shop, leaf, 1);
        as(shop.seller());
        UUID saleId = saleService.create(new com.cns.spring_house.sale.dto.CreateSaleRequestDTO(
                null, null, TaxMode.UNRESOLVED, name)).id();
        addSeen(saleId, special(leaf));
        SecurityContextHolder.clearContext();
        return saleId;
    }

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
        return new Shop(workshop, model,
                account(workshop, "SELLER"),
                account(workshop, "CONTROL"),
                account(workshop, "OPERATOR"),
                account(workshop, "ADMIN"));
    }

    private Account account(Workshop workshop, String roleName) {
        Employee employee = employeeRepository.save(Employee.builder()
                .workshop(workshop)
                .firstName("NOMBRE")
                .lastName(roleName + " " + unique())
                .dpi(UUID.randomUUID().toString().substring(0, 13))
                .birthDate(LocalDate.of(1990, 1, 1))
                .build());
        Role role = roleRepository.findAll().stream()
                .filter(candidate -> roleName.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        UserAccount account = userAccountRepository.save(UserAccount.builder()
                .employee(employee)
                .role(role)
                .username("u_" + unique())
                .password("no-usada-en-pruebas")
                .isActive(true)
                .build());
        return new Account(account.getId(), workshop.getId(), roleName);
    }

    /** Un cliente registrado (NIT) del maestro, por SQL: no hace falta su servicio. */
    private UUID customer() {
        UUID id = UUID.randomUUID();
        String nit = String.valueOf(10_000_000L + (long) (Math.random() * 80_000_000L));
        jdbc.update("INSERT INTO customer (id, nit, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
                id, nit, "CLIENTE " + unique());
        return id;
    }

    private Product leaf(Shop shop, int length, int curls) {
        Family hr = familyRepository.findAll().stream()
                .filter(family -> "HR".equals(family.getCode()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("largo", length);
        attributes.put("tieneOrificio", false);
        attributes.put("cantidadColochos", curls);
        return productRepository.save(Product.builder()
                .family(hr)
                .model(shop.model())
                .name("HOJA " + length + " " + unique())
                .sku("SKU-" + unique())
                .attributes(attributes)
                .build());
    }

    private Product normalProduct(String name) {
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

    /** Inventario con `units` unidades disponibles y el lote comprado que las respalda. */
    private List<UUID> stocked(Shop shop, Product product, int units) {
        Inventory inventory = inventoryRepository.save(Inventory.builder()
                .product(product)
                .workshop(shop.workshop())
                .price(PRICE)
                .cost(COST)
                .stock(units)
                .minStock(0)
                .build());
        Supplier supplier = supplierRepository.save(Supplier.builder()
                .workshop(shop.workshop())
                .name("PROVEEDOR " + unique())
                .build());
        acquisitionRepository.save(InventoryAcquisition.builder()
                .workshop(shop.workshop())
                .inventory(inventory)
                .supplier(supplier)
                .origin(AcquisitionOrigin.PURCHASE)
                .acquisitionType(AcquisitionType.NATIONAL)
                .quantityReceived(units)
                .quantityRemaining(units)
                .unitCost(COST)
                .acquiredAt(LocalDateTime.now())
                .build());
        List<UUID> items = new ArrayList<>();
        for (int unit = 0; unit < units; unit++) {
            items.add(itemRepository.save(InventoryItem.builder()
                    .inventory(inventory)
                    .status(InventoryItemStatus.AVAILABLE)
                    .build()).getId());
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

    private static AddSaleDetailRequestDTO special(Product product) {
        return new AddSaleDetailRequestDTO(product.getId(), null, null, 1, PRICE, null, null);
    }

    /** Un servicio SIN placa en la linea: la placa es de la venta, y opcional. */
    private static AddSaleDetailRequestDTO serviceLine(Product service) {
        return new AddSaleDetailRequestDTO(
                service.getId(), null, null, 1, new BigDecimal("150.00"), null, null, null);
    }

    private SaleResponseDTO addSeen(UUID saleId, AddSaleDetailRequestDTO dto) {
        return saleService.addDetail(saleId, SeenCatalog.seen(jdbc, dto));
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
