package com.cns.spring_house.sale.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.role.entity.Role;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.DispatchSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleDetailResponseDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.SaleStatus;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.service.SaleService;
import com.cns.spring_house.shared.exception.ValidationException;
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
 * "Pedidos por liberar" paso de Control al Operador, por HTTP de verdad.
 *
 * Todo pasa por la cadena real: filtro JWT, `@PreAuthorize`, controlador,
 * servicio con su transaccion y PostgreSQL. Cada cuenta es real (empleado,
 * rol, taller), porque quien libera queda registrado en la linea.
 *
 * Lo que se comprueba:
 *
 * <ul>
 * <li>el Operador lista, inspecciona y libera las entregas de SU taller;</li>
 * <li>Control —y cualquier otro rol, o nadie— recibe 403/401 en esas rutas
 * aunque llame directo a la API, y nada cambia en la base;</li>
 * <li>otro taller recibe 404, sin fuga de datos ni accion;</li>
 * <li>ADMIN conserva la supervision que ya tenia;</li>
 * <li>dos Operadores a la vez: un solo efecto; repetir, estados invalidos y una
 * falla al confirmar no escriben nada;</li>
 * <li>la comanda mixta (1 de stock + 2 a fabricar) conserva las lineas del
 * vendedor y se completa una sola vez;</li>
 * <li>las demas funciones de Control (proveedores) siguen igual.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ReleaseQueueOperatorHttpDatabaseTest {

    private static final String QUEUE = "/v1/fulfillments/control";
    private static final String FULFILLMENTS = "/v1/fulfillments";
    private static final BigDecimal PRICE = new BigDecimal("500.00");
    private static final BigDecimal COST = new BigDecimal("100.00");

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

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision("release_queue_operator_http_it");
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

    // ── 1, 2. El Operador lista, inspecciona y libera ────────────

    @Test
    void theOperatorListsInspectsAndReleasesItsWorkshopsPieceExactlyOnce() throws Exception {
        Shop shop = shop();
        Shop foreign = shop();
        Seeded mine = dispatchedRelease(shop, 3, "Carlos");
        Seeded theirs = dispatchedRelease(foreign, 1, "Ajeno");
        UUID other = mine.others().get(0);
        Map<String, Object> before = stateOf(mine.saleId());

        // La cola: lo de su taller, con la especificacion y el area de entrega.
        JsonNode queue = body(http.perform(get(QUEUE).param("status", "QUEUED").with(shop.operator().auth()))
                .andExpect(status().isOk())
                .andReturn());
        List<String> ids = idsOf(queue);
        assertThat(ids).contains(mine.lineId().toString()).doesNotContain(theirs.lineId().toString());
        JsonNode task = taskOf(queue, mine.lineId());
        assertThat(task.get("type").asString()).isEqualTo("STOCK_RELEASE");
        assertThat(task.get("area").asString()).isEqualTo("CONTROL");
        assertThat(task.get("status").asString()).isEqualTo("QUEUED");
        assertThat(task.get("itemId").asString()).isEqualTo(mine.reserved().toString());
        assertThat(task.get("availableStock").asInt()).isEqualTo(2);

        // Detalle y unidades: la reservada y las libres del MISMO producto.
        http.perform(get(FULFILLMENTS + "/" + mine.lineId()).with(shop.operator().auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(mine.lineId().toString()));
        List<String> eligible = idsOf(body(http.perform(
                        get(FULFILLMENTS + "/" + mine.lineId() + "/eligible-items").with(shop.operator().auth()))
                .andExpect(status().isOk())
                .andReturn()));
        assertThat(eligible).containsExactlyInAnyOrder(
                mine.reserved().toString(), mine.others().get(0).toString(), mine.others().get(1).toString());
        // Leer no escribe.
        assertThat(stateOf(mine.saleId())).isEqualTo(before);

        // Liberar otra unidad libre igual, por HTTP.
        http.perform(release(mine.lineId(), other).with(shop.operator().auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("status").value("FULFILLED"))
                .andExpect(jsonPath("itemId").value(other.toString()))
                .andExpect(jsonPath("assignedTo").value(shop.operator().fullName()));

        Map<String, Object> line = lineRow(mine.lineId());
        assertThat(line.get("fulfillment_status")).isEqualTo("FULFILLED");
        assertThat(line.get("item_id")).isEqualTo(other);
        // Quien libero queda registrado: el Operador, no Control.
        assertThat(line.get("fulfillment_employee_id")).isEqualTo(shop.operator().employeeId());
        assertThat(line.get("fulfillment_completed_at")).isNotNull();
        assertThat(itemStatus(other)).isEqualTo("SOLD");
        assertThat(itemStatus(mine.reserved())).isEqualTo("AVAILABLE");
        // Unidad por unidad del mismo inventario: stock, lotes, costo, precio
        // y lineas no se mueven; nada se cobra dos veces.
        assertUnchangedMoneyAndStock(before, stateOf(mine.saleId()));
        assertThat(saleRow(mine.saleId()).get("fulfillment_status")).isEqualTo("READY_TO_COMPLETE");

        // Repetir: el mismo 400 de siempre y ninguna escritura.
        Map<String, Object> released = stateOf(mine.saleId());
        http.perform(release(mine.lineId(), null).with(shop.operator().auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("La solicitud ya fue entregada"));
        assertThat(stateOf(mine.saleId())).isEqualTo(released);
        // Ya entregada, solo se ofrece la unidad que quedo en la venta.
        assertThat(idsOf(body(http.perform(
                        get(FULFILLMENTS + "/" + mine.lineId() + "/eligible-items").with(shop.operator().auth()))
                .andExpect(status().isOk())
                .andReturn()))).containsExactly(other.toString());

        // La pestana de liberadas la muestra; la de pendientes ya no.
        assertThat(idsOf(body(http.perform(get(QUEUE).param("status", "FULFILLED").with(shop.operator().auth()))
                .andExpect(status().isOk()).andReturn()))).contains(mine.lineId().toString());
        assertThat(idsOf(body(http.perform(get(QUEUE).with(shop.operator().auth()))
                .andExpect(status().isOk()).andReturn()))).doesNotContain(mine.lineId().toString());

        // El vendedor ve la unidad entregada y completa una sola linea, una vez.
        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(mine.saleId());
        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        assertThat(completed.details()).hasSize(1);
        assertThat(completed.details().get(0).id()).isEqualTo(mine.lineId());
        assertThat(completed.details().get(0).item().id()).isEqualTo(other);
        assertThat(completed.total()).isEqualByComparingTo(PRICE);
    }

    // ── 3, 11. Control ya no; sus otras funciones, si ────────────

    @Test
    void aControlOnlyAccountIsRejectedOnEveryTransferredEndpointAndWritesNothing() throws Exception {
        Shop shop = shop();
        Seeded seeded = dispatchedRelease(shop, 2, "Control");
        Map<String, Object> before = stateOf(seeded.saleId());
        RequestPostProcessor control = shop.control().auth();

        http.perform(get(QUEUE).with(control)).andExpect(status().isForbidden());
        http.perform(get(QUEUE).param("status", "FULFILLED").with(control)).andExpect(status().isForbidden());
        http.perform(get(FULFILLMENTS + "/" + seeded.lineId()).with(control)).andExpect(status().isForbidden());
        http.perform(get(FULFILLMENTS + "/" + seeded.lineId() + "/eligible-items").with(control))
                .andExpect(status().isForbidden());
        http.perform(release(seeded.lineId(), null).with(control)).andExpect(status().isForbidden());
        http.perform(release(seeded.lineId(), seeded.others().get(0)).with(control))
                .andExpect(status().isForbidden());
        http.perform(post(FULFILLMENTS + "/" + seeded.lineId() + "/release").with(control))
                .andExpect(status().isForbidden());

        assertThat(stateOf(seeded.saleId())).isEqualTo(before);
        assertThat(lineRow(seeded.lineId()).get("fulfillment_status")).isEqualTo("QUEUED");

        // Lo demas de Control no se movio: proveedores sigue siendo suyo (y
        // de ADMIN), y no se le abrio al Operador.
        http.perform(get("/v1/suppliers").with(control)).andExpect(status().isOk());
        http.perform(get("/v1/suppliers").with(shop.operator().auth())).andExpect(status().isForbidden());
    }

    // ── 5. Otros roles y anonimo ─────────────────────────────────

    @Test
    void otherRolesAreForbiddenAndAnonymousIsUnauthorized() throws Exception {
        Shop shop = shop();
        Seeded seeded = dispatchedRelease(shop, 1, "Roles");
        Map<String, Object> before = stateOf(seeded.saleId());

        // Sin token, antes de cualquier peticion autenticada: `springSecurity()`
        // reaplica el ultimo `jwt()` del hilo a las peticiones que no traen uno.
        TestSecurityContextHolder.clearContext();
        for (MockHttpServletRequestBuilder call : transferredCalls(seeded)) {
            http.perform(call).andExpect(status().isUnauthorized());
        }
        for (String role : List.of("SELLER", "POLICE", "SECRETARY", "CONTROL")) {
            RequestPostProcessor auth = token(shop.workshop(), shop.seller().accountId(), role);
            for (MockHttpServletRequestBuilder call : transferredCalls(seeded)) {
                http.perform(call.with(auth)).andExpect(status().isForbidden());
            }
        }
        assertThat(stateOf(seeded.saleId())).isEqualTo(before);
    }

    // ── 4. Otro taller: 404, sin fuga ni accion ──────────────────

    @Test
    void anotherWorkshopGetsNotFoundAndCannotSwapInItsOwnUnits() throws Exception {
        Shop shop = shop();
        Shop foreign = shop();
        Seeded seeded = dispatchedRelease(shop, 2, "Aislado");
        Seeded foreignSale = dispatchedRelease(foreign, 2, "Vecino");
        Map<String, Object> before = stateOf(seeded.saleId());
        Map<String, Object> foreignBefore = stateOf(foreignSale.saleId());

        for (Account outsider : List.of(foreign.operator(), foreign.admin())) {
            http.perform(get(FULFILLMENTS + "/" + seeded.lineId()).with(outsider.auth()))
                    .andExpect(status().isNotFound());
            http.perform(get(FULFILLMENTS + "/" + seeded.lineId() + "/eligible-items").with(outsider.auth()))
                    .andExpect(status().isNotFound());
            http.perform(release(seeded.lineId(), null).with(outsider.auth()))
                    .andExpect(status().isNotFound());
            assertThat(idsOf(body(http.perform(get(QUEUE).with(outsider.auth()))
                    .andExpect(status().isOk()).andReturn())))
                    .doesNotContain(seeded.lineId().toString());
        }
        // Un token de un taller NO puede entregar a su venta una unidad de otro:
        // la unidad elegida tambien se busca en el taller del token.
        http.perform(release(seeded.lineId(), foreignSale.others().get(0)).with(shop.operator().auth()))
                .andExpect(status().isNotFound());

        assertThat(stateOf(seeded.saleId())).isEqualTo(before);
        assertThat(stateOf(foreignSale.saleId())).isEqualTo(foreignBefore);
    }

    // ── 6. ADMIN conserva la supervision ─────────────────────────

    @Test
    void anAdminOfTheSameWorkshopKeepsItsExistingSupervisionAccess() throws Exception {
        Shop shop = shop();
        Seeded seeded = dispatchedRelease(shop, 1, "Admin");

        assertThat(idsOf(body(http.perform(get(QUEUE).with(shop.admin().auth()))
                .andExpect(status().isOk()).andReturn()))).contains(seeded.lineId().toString());
        http.perform(release(seeded.lineId(), null).with(shop.admin().auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("status").value("FULFILLED"));
        assertThat(lineRow(seeded.lineId()).get("fulfillment_employee_id")).isEqualTo(shop.admin().employeeId());
    }

    // ── 7. Dos Operadores a la vez ───────────────────────────────

    /**
     * Las dos peticiones se SOLAPAN de verdad: una transaccion aparte retiene
     * la venta, ambas quedan esperando su cerrojo en PostgreSQL y solo entonces
     * se suelta. Gana una; la otra lee ya la entrega hecha y recibe el 400 de
     * siempre. Una unidad sale, una vez, y queda a nombre de quien gano.
     */
    @Test
    void twoOperatorsReleasingTheSamePieceAtOnceDeliverExactlyOnce() throws Exception {
        Shop shop = shop();
        Seeded seeded = dispatchedRelease(shop, 3, "Doble");
        Map<String, Object> before = stateOf(seeded.saleId());
        List<Map.Entry<Account, UUID>> attempts = List.of(
                Map.entry(shop.operator(), seeded.others().get(0)),
                Map.entry(shop.operator2(), seeded.others().get(1)));

        List<Integer> statuses = new ArrayList<>();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM sale WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, seeded.saleId());
                lock.executeQuery();
            }
            ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> calls = new ArrayList<>();
                for (Map.Entry<Account, UUID> attempt : attempts) {
                    calls.add(pool.submit(() -> {
                        go.await();
                        return http.perform(release(seeded.lineId(), attempt.getValue())
                                        .with(attempt.getKey().auth()))
                                .andReturn().getResponse().getStatus();
                    }));
                }
                go.countDown();
                awaitWaitingOnLocks(attempts.size());
                holder.rollback();
                for (Future<Integer> call : calls) {
                    statuses.add(call.get(30, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        Map<String, Object> line = lineRow(seeded.lineId());
        UUID delivered = (UUID) line.get("item_id");
        assertThat(delivered).isIn(seeded.others().get(0), seeded.others().get(1));
        UUID winner = delivered.equals(seeded.others().get(0))
                ? shop.operator().employeeId()
                : shop.operator2().employeeId();
        assertThat(line.get("fulfillment_employee_id")).isEqualTo(winner);
        assertThat(soldUnitsOf(seeded.productId())).isEqualTo(1L);
        assertThat(itemStatus(delivered)).isEqualTo("SOLD");
        assertThat(itemStatus(seeded.reserved())).isEqualTo("AVAILABLE");
        assertUnchangedMoneyAndStock(before, stateOf(seeded.saleId()));
    }

    // ── 8. Estados invalidos: rechazados sin escribir ────────────

    @Test
    void invalidAndStaleReleasesAreRejectedWithoutWrites() throws Exception {
        Shop shop = shop();
        Product leaf = leaf(shop, 140, 2);
        List<UUID> units = stocked(shop, leaf, 2);
        Product missing = leaf(shop, 70, 2);
        emptyInventory(shop, missing);
        Product unrelated = leaf(shop, 60, 2);
        UUID unrelatedUnit = stocked(shop, unrelated, 1).get(0);

        as(shop.seller());
        // Sin enviar: la unidad ya esta reservada pero no esta en la cola.
        SaleResponseDTO draft = addSeen(openSale("Borrador").id(), special(leaf, 1));
        UUID draftLine = draft.details().get(0).id();
        // Otra venta reserva la otra unidad y se envia.
        SaleResponseDTO other = saleService.dispatch(
                addSeen(openSale("Otra").id(), special(leaf, 1)).id(), new DispatchSaleRequestDTO(null));
        UUID reservedForOther = other.details().get(0).item().id();
        // Y una venta enviada con una pieza a fabricar.
        SaleResponseDTO toFabricate = saleService.dispatch(
                addSeen(openSale("Fabricar").id(), special(missing, 1)).id(), new DispatchSaleRequestDTO(null));
        UUID transformationLine = toFabricate.details().get(0).id();
        assertThat(toFabricate.details().get(0).fulfillment().type()).isEqualTo(FulfillmentType.TRANSFORMATION);
        SecurityContextHolder.clearContext();
        assertThat(units).contains(reservedForOther);

        RequestPostProcessor operator = shop.operator().auth();
        Map<String, Object> draftBefore = stateOf(draft.id());
        Map<String, Object> otherBefore = stateOf(other.id());
        Map<String, Object> fabricateBefore = stateOf(toFabricate.id());

        http.perform(release(draftLine, null).with(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("La solicitud todavia no se ha enviado al taller"));
        http.perform(release(transformationLine, null).with(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("La solicitud no es una entrega de stock"));
        http.perform(get(FULFILLMENTS + "/" + transformationLine + "/eligible-items").with(operator))
                .andExpect(status().isBadRequest());
        UUID otherLine = other.details().get(0).id();
        // La unidad de otra venta ya no esta libre: se re-lee bajo el cerrojo.
        http.perform(release(otherLine, unitOtherThan(units, reservedForOther)).with(operator))
                .andExpect(status().isBadRequest());
        http.perform(release(otherLine, unrelatedUnit).with(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("La unidad elegida no es del producto solicitado"));
        http.perform(release(UUID.randomUUID(), null).with(operator)).andExpect(status().isNotFound());
        http.perform(release(otherLine, UUID.randomUUID()).with(operator)).andExpect(status().isNotFound());
        // La transferencia no le da al Operador estados nuevos sobre una entrega.
        http.perform(post(FULFILLMENTS + "/" + otherLine + "/start").with(operator))
                .andExpect(status().isBadRequest());
        http.perform(post(FULFILLMENTS + "/" + otherLine + "/block").with(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());

        assertThat(stateOf(draft.id())).isEqualTo(draftBefore);
        assertThat(stateOf(other.id())).isEqualTo(otherBefore);
        assertThat(stateOf(toFabricate.id())).isEqualTo(fabricateBefore);
    }

    // ── 9. Falla al confirmar: todo se deshace ───────────────────

    @Test
    void aFailureAtCommitRollsBackTheUnitSwapTheStatusAndTheActor() throws Exception {
        Shop shop = shop();
        Seeded seeded = dispatchedRelease(shop, 2, "Falla");
        UUID other = seeded.others().get(0);
        Map<String, Object> before = stateOf(seeded.saleId());

        failingAtCommit(seeded.lineId(), () -> {
            try {
                http.perform(release(seeded.lineId(), other).with(shop.operator().auth()))
                        .andExpect(status().is5xxServerError());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });

        // Ni la unidad cambiada, ni la entrega, ni quien la hizo, ni la venta.
        assertThat(stateOf(seeded.saleId())).isEqualTo(before);
        assertThat(itemStatus(other)).isEqualTo("AVAILABLE");
        assertThat(itemStatus(seeded.reserved())).isEqualTo("SOLD");
        assertThat(lineRow(seeded.lineId()).get("fulfillment_employee_id")).isNull();

        // Y no queda atascada: sin la falla, se libera normalmente.
        http.perform(release(seeded.lineId(), other).with(shop.operator().auth()))
                .andExpect(status().isOk());
        assertThat(lineRow(seeded.lineId()).get("item_id")).isEqualTo(other);
    }

    // ── 10. Comanda mixta: 1 de stock + 2 a fabricar ─────────────

    @Test
    void aMixedRequestKeepsTheSellersLinesAndCompletesOnceAfterReleaseAndFabrication() throws Exception {
        Shop shop = shop();
        Product leaf = leaf(shop, 150, 2);
        stocked(shop, leaf, 1);
        Product longLeaf = leaf(shop, 300, 1);
        List<UUID> sources = stocked(shop, longLeaf, 2);

        as(shop.seller());
        SaleResponseDTO sale = addSeen(openSale("Mixta").id(), special(leaf, 3));
        assertThat(sale.details()).hasSize(3);
        List<UUID> originalLines = sale.details().stream().map(SaleDetailResponseDTO::id).toList();
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        UUID saleId = sale.id();
        UUID stockLine = lineOf(sale, FulfillmentType.STOCK_RELEASE);
        List<UUID> fabricationLines = sale.details().stream()
                .filter(detail -> detail.fulfillment().type() == FulfillmentType.TRANSFORMATION)
                .map(SaleDetailResponseDTO::id)
                .toList();
        assertThat(fabricationLines).hasSize(2);
        assertThatThrownBy(() -> completeAsCf(saleId))
                .isInstanceOf(ValidationException.class);
        SecurityContextHolder.clearContext();

        // Cada pagina del Operador muestra SOLO lo suyo de la venta, sin duplicar.
        JsonNode releases = body(http.perform(get(QUEUE).with(shop.operator().auth()))
                .andExpect(status().isOk()).andReturn());
        List<JsonNode> releaseTasks = tasksOfSale(releases, saleId);
        assertThat(releaseTasks).extracting(node -> node.get("id").asString())
                .containsExactly(stockLine.toString());
        assertThat(releaseTasks.get(0).get("saleProgress").get("total").asInt()).isEqualTo(1);
        JsonNode workshop = body(http.perform(get(FULFILLMENTS + "/operator").with(shop.operator().auth()))
                .andExpect(status().isOk()).andReturn());
        List<JsonNode> workshopTasks = tasksOfSale(workshop, saleId);
        assertThat(workshopTasks).extracting(node -> node.get("id").asString())
                .containsExactlyInAnyOrderElementsOf(fabricationLines.stream().map(UUID::toString).toList());
        assertThat(workshopTasks.get(0).get("saleProgress").get("total").asInt()).isEqualTo(2);

        // Liberar la de stock no completa la venta: faltan las fabricadas.
        http.perform(release(stockLine, null).with(shop.operator().auth())).andExpect(status().isOk());
        assertThat(saleRow(saleId).get("fulfillment_status")).isEqualTo("IN_PROGRESS");
        for (int index = 0; index < fabricationLines.size(); index++) {
            http.perform(post(FULFILLMENTS + "/" + fabricationLines.get(index) + "/transform")
                            .with(shop.operator().auth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inputItem\":\"" + sources.get(index) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("status").value("FULFILLED"));
        }
        assertThat(saleRow(saleId).get("fulfillment_status")).isEqualTo("READY_TO_COMPLETE");

        as(shop.seller());
        SaleResponseDTO completed = completeAsCf(saleId);
        assertThat(completed.status()).isEqualTo(SaleStatus.COMPLETED);
        // Las mismas tres lineas del vendedor —ni una mas—, cada una con su
        // propia unidad, y el total de tres piezas cobradas una vez.
        assertThat(completed.details()).extracting(SaleDetailResponseDTO::id)
                .containsExactlyInAnyOrderElementsOf(originalLines);
        assertThat(completed.details()).allSatisfy(detail -> assertThat(detail.quantity()).isEqualTo(1));
        assertThat(completed.details()).extracting(detail -> detail.item().id()).doesNotHaveDuplicates();
        assertThat(completed.total()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sale_detail WHERE sale_id = ?", Long.class, saleId))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale_detail WHERE sale_id = ? AND fulfillment_employee_id = ?",
                Long.class, saleId, shop.operator().employeeId())).isEqualTo(3L);
    }

    // ── Llamadas ─────────────────────────────────────────────────

    private MockHttpServletRequestBuilder release(UUID line, UUID item) {
        MockHttpServletRequestBuilder request = post(FULFILLMENTS + "/" + line + "/release")
                .contentType(MediaType.APPLICATION_JSON);
        return item == null ? request.content("{}") : request.content("{\"item\":\"" + item + "\"}");
    }

    private List<MockHttpServletRequestBuilder> transferredCalls(Seeded seeded) {
        return List.of(
                get(QUEUE),
                get(FULFILLMENTS + "/" + seeded.lineId()),
                get(FULFILLMENTS + "/" + seeded.lineId() + "/eligible-items"),
                release(seeded.lineId(), null));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> idsOf(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    private static JsonNode taskOf(JsonNode array, UUID id) {
        for (JsonNode node : array) {
            if (node.get("id").asString().equals(id.toString())) {
                return node;
            }
        }
        throw new AssertionError("La cola no trae la solicitud " + id);
    }

    private static List<JsonNode> tasksOfSale(JsonNode array, UUID saleId) {
        List<JsonNode> tasks = new ArrayList<>();
        array.forEach(node -> {
            if (node.get("saleId").asString().equals(saleId.toString())) {
                tasks.add(node);
            }
        });
        return tasks;
    }

    // ── Estado en la base ────────────────────────────────────────

    /** Todo lo que una entrega podria tocar, fila por fila. */
    private Map<String, Object> stateOf(UUID saleId) {
        UUID workshop = jdbc.queryForObject("SELECT workshop_id FROM sale WHERE id = ?", UUID.class, saleId);
        Map<String, Object> state = new HashMap<>();
        state.put("sale", jdbc.queryForMap("SELECT * FROM sale WHERE id = ?", saleId));
        state.put("lines", jdbc.queryForList("SELECT * FROM sale_detail WHERE sale_id = ? ORDER BY id", saleId));
        state.put("inventory", jdbc.queryForList(
                "SELECT * FROM inventory WHERE workshop_id = ? ORDER BY id", workshop));
        state.put("items", jdbc.queryForList(
                "SELECT item.* FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE i.workshop_id = ? ORDER BY item.id", workshop));
        state.put("lots", jdbc.queryForList(
                "SELECT * FROM inventory_acquisition WHERE workshop_id = ? ORDER BY id", workshop));
        state.put("allocations", jdbc.queryForList(
                "SELECT a.* FROM sale_detail_cost_allocation a JOIN sale_detail d ON d.id = a.sale_detail_id "
                        + "WHERE d.sale_id = ? ORDER BY a.id", saleId));
        // La base es solo de esta clase y sus pruebas corren una tras otra.
        state.put("work", jdbc.queryForList("SELECT * FROM work_result ORDER BY id"));
        state.put("transformations", jdbc.queryForObject(
                "SELECT COUNT(*) FROM transformation", Long.class));
        return state;
    }

    /**
     * Entre dos estados, lo que una entrega NO puede cambiar: lineas (cuantas,
     * precio, cantidad, subtotal, costo), total de la venta, existencias,
     * lotes y asignaciones de costo.
     */
    @SuppressWarnings("unchecked")
    private void assertUnchangedMoneyAndStock(Map<String, Object> before, Map<String, Object> after) {
        List<Map<String, Object>> linesBefore = (List<Map<String, Object>>) before.get("lines");
        List<Map<String, Object>> linesAfter = (List<Map<String, Object>>) after.get("lines");
        assertThat(linesAfter).hasSameSizeAs(linesBefore);
        for (int index = 0; index < linesBefore.size(); index++) {
            for (String column : List.of("id", "price", "quantity", "sub_total", "cost_total", "product_id")) {
                assertThat(linesAfter.get(index).get(column)).as(column)
                        .isEqualTo(linesBefore.get(index).get(column));
            }
        }
        assertThat(((Map<String, Object>) after.get("sale")).get("total"))
                .isEqualTo(((Map<String, Object>) before.get("sale")).get("total"));
        assertThat(after.get("inventory")).isEqualTo(before.get("inventory"));
        assertThat(after.get("lots")).isEqualTo(before.get("lots"));
        assertThat(after.get("allocations")).isEqualTo(before.get("allocations"));
    }

    private Map<String, Object> lineRow(UUID line) {
        return jdbc.queryForMap("SELECT * FROM sale_detail WHERE id = ?", line);
    }

    private Map<String, Object> saleRow(UUID saleId) {
        return jdbc.queryForMap("SELECT * FROM sale WHERE id = ?", saleId);
    }

    private String itemStatus(UUID item) {
        return jdbc.queryForObject("SELECT status FROM inventory_item WHERE id = ?", String.class, item);
    }

    private long soldUnitsOf(UUID productId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_item item JOIN inventory i ON i.id = item.inventory_id "
                        + "WHERE i.product_id = ? AND item.status = 'SOLD'", Long.class, productId);
    }

    private static UUID unitOtherThan(List<UUID> units, UUID excluded) {
        return units.stream().filter(unit -> !unit.equals(excluded)).findFirst().orElseThrow();
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
        throw new IllegalStateException("Las liberaciones nunca llegaron a esperar el cerrojo de la venta");
    }

    /** Solo para esta prueba y esta linea: un trigger que falla al confirmar. */
    private void failingAtCommit(UUID line, Runnable body) {
        jdbc.execute("CREATE OR REPLACE FUNCTION test_release_fail_at_commit() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falla forzada de prueba'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_release_fail_at_commit AFTER UPDATE ON sale_detail "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id = '" + line + "'::uuid) "
                + "EXECUTE FUNCTION test_release_fail_at_commit()");
        try {
            body.run();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_release_fail_at_commit ON sale_detail");
            jdbc.execute("DROP FUNCTION IF EXISTS test_release_fail_at_commit()");
        }
    }

    // ── Datos ────────────────────────────────────────────────────

    private record Account(UUID accountId, UUID employeeId, UUID workshopId, String role, String fullName) {

        RequestPostProcessor auth() {
            return token(workshopId, accountId, role);
        }
    }

    private record Shop(
            Workshop workshop,
            Model model,
            Account seller,
            Account control,
            Account operator,
            Account operator2,
            Account admin) {
    }

    private record Seeded(UUID saleId, UUID lineId, UUID productId, UUID reserved, List<UUID> others) {
    }

    /** Un JWT como el del login: sujeto = cuenta, `workshop` y `role`. */
    private static RequestPostProcessor token(Workshop workshop, UUID account, String role) {
        return token(workshop.getId(), account, role);
    }

    private static RequestPostProcessor token(UUID workshop, UUID account, String role) {
        return jwt().jwt(builder -> builder.subject(account.toString())
                        .claim("workshop", workshop.toString())
                        .claim("role", role))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
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

    /** Una venta enviada con UNA pieza especial reservada de `units` en stock. */
    private Seeded dispatchedRelease(Shop shop, int units, String customer) {
        Product leaf = leaf(shop, 120, 2);
        List<UUID> stocked = stocked(shop, leaf, units);
        as(shop.seller());
        SaleResponseDTO sale = addSeen(openSale(customer).id(), special(leaf, 1));
        sale = saleService.dispatch(sale.id(), new DispatchSaleRequestDTO(null));
        SecurityContextHolder.clearContext();
        SaleDetailResponseDTO line = sale.details().get(0);
        assertThat(line.fulfillment().type()).isEqualTo(FulfillmentType.STOCK_RELEASE);
        UUID reserved = line.item().id();
        return new Seeded(sale.id(), line.id(), leaf.getId(), reserved,
                stocked.stream().filter(unit -> !unit.equals(reserved)).toList());
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
        return new Account(account.getId(), employee.getId(), workshop.getId(), roleName,
                employee.getFirstName() + " " + employee.getLastName());
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

    private SaleResponseDTO openSale(String customer) {
        return saleService.create(new CreateSaleRequestDTO(null, "P123ABC", TaxMode.UNRESOLVED, customer));
    }

    private static AddSaleDetailRequestDTO special(Product product, int quantity) {
        return new AddSaleDetailRequestDTO(product.getId(), null, null, quantity, PRICE, null, null);
    }

    private SaleResponseDTO addSeen(UUID saleId, AddSaleDetailRequestDTO dto) {
        return saleService.addDetail(saleId, SeenCatalog.seen(jdbc, dto));
    }

    private SaleResponseDTO completeAsCf(UUID saleId) {
        return saleService.completeSale(saleId, new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH, TaxMode.CF, null, null));
    }

    private static UUID lineOf(SaleResponseDTO sale, FulfillmentType type) {
        return sale.details().stream()
                .filter(detail -> detail.fulfillment().type() == type)
                .map(SaleDetailResponseDTO::id)
                .findFirst()
                .orElseThrow();
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
