package com.cns.spring_house.shared.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.employee.repository.EmployeeRepository;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.inventory.dto.*;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.service.InventoryService;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.product.service.ProductService;
import com.cns.spring_house.role.repository.RoleRepository;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.supplier.repository.SupplierRepository;
import com.cns.spring_house.testsupport.TestDatabases;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.transformation.service.TransformationService;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and production transactional services, with independent committed calls.
 * This class only recreates barcode_workflow_it; it never resets the development database.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class BarcodeWorkflowDatabaseTest {
    private static final String TEMPORARY_DATABASE = "barcode_workflow_it";
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.cns.spring_house.brand.repository.BrandRepository brands;
    @Autowired private com.cns.spring_house.model.repository.ModelRepository models;
    @Autowired private InventoryService inventoryService;
    @Autowired private ProductService productService;
    @Autowired private TransformationService transformationService;
    @Autowired private ProductRepository products;
    @Autowired private InventoryItemRepository items;
    @Autowired private WorkshopRepository workshops;
    @Autowired private FamilyRepository families;
    @Autowired private SupplierRepository suppliers;
    @Autowired private EmployeeRepository employees;
    @Autowired private UserAccountRepository accounts;
    @Autowired private RoleRepository roles;
    private Workshop workshop;
    private Jwt token;
    private Supplier supplier;
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

    @BeforeEach void authenticateWorkshop() {
        workshop = workshops.save(Workshop.builder().name("BARCODE " + unique())
                .address("Test").isActive(true).build());
        Employee employee = employees.save(Employee.builder().workshop(workshop)
                .firstName("Barcode").lastName("Test").dpi(unique() + "12345")
                .birthDate(LocalDate.of(1990, 1, 1)).build());
        UserAccount account = accounts.save(UserAccount.builder().employee(employee)
                .role(roles.findAll().stream().filter(r -> r.getName().equals("CONTROL")).findFirst().orElseThrow())
                .username("barcode" + unique()).password("test-only").build());
        token = Jwt.withTokenValue("test-only").header("alg", "HS256")
                .subject(account.getId().toString()).claim("workshop", workshop.getId().toString()).build();
        authenticate(token);
        supplier = suppliers.save(Supplier.builder().name("Supplier " + unique()).workshop(workshop).build());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    private String unique() { return UUID.randomUUID().toString().substring(0, 8); }
    private Product product(FamilyCategory category) {
        Family family = families.save(Family.builder().code(unique()).name("Family " + unique())
                .category(category).requiresModel(false).attributes(new ArrayList<>()).build());
        return products.save(Product.builder().name("Product " + unique()).sku("SKU-" + unique())
                .family(family).attributes(Map.of()).build());
    }
    private InventoryResponseDTO inventory(Product product) {
        return inventoryService.create(new CreateInventoryRequestDTO(product.getId(),
                BigDecimal.TEN, BigDecimal.ONE, 0));
    }
    private InventoryResponseDTO purchase(InventoryResponseDTO inventory, int quantity) {
        return inventoryService.addStock(inventory.id(), new AddStockRequestDTO(quantity,
                supplier.getId(), AcquisitionType.NATIONAL, BigDecimal.ONE, null));
    }
    private InventoryResponseDTO assemble(InventoryResponseDTO container, Product component) {
        return inventoryService.addStock(container.id(), new AddStockRequestDTO(null,
                null, null, null, List.of(component.getId())));
    }
    private List<String> codes(InventoryResponseDTO response) {
        return response.mutation().createdItems().stream().map(InventoryItemResponseDTO::barcodeValue).toList();
    }

    @Test void normalFirstUseAndLaterStockReuseTheGlobalProductIdentity() {
        Product product = product(FamilyCategory.NORMAL);
        var created = inventory(product);
        String code = productService.get(product.getId()).barcodeValue();
        assertThat(code).matches("P1-[0-9]{40}");
        assertThat(created.mutation().productBarcodeFirstUse()).isTrue();
        assertThat(purchase(created, 3).mutation().productBarcodeFirstUse()).isFalse();
        assertThat(purchase(created, 5).product().barcodeValue()).isEqualTo(code);
        assertThat(inventoryService.get(created.id()).product().barcodeValue()).isEqualTo(code);
        assertThat(inventoryService.getItems(created.id())).isEmpty();
    }
    @Test void normalIdentitySurvivesSkuNamePriceAndAttributeChanges() {
        Product product = product(FamilyCategory.NORMAL);
        var created = inventory(product);
        String code = created.product().barcodeValue();
        var updated = productService.update(product.getId(), new UpdateProductRequestDTO(
                "CHANGED-" + unique(), "Changed name", null, null, Map.of()));
        inventoryService.update(created.id(), new UpdateInventoryRequestDTO(
                BigDecimal.valueOf(20), BigDecimal.valueOf(2), 1));
        assertThat(updated.barcodeValue()).isEqualTo(code);
        assertThat(inventoryService.get(created.id()).product().barcodeValue()).isEqualTo(code);
    }
    @Test void sameNormalProductInAnotherWorkshopNeverCreatesAnotherBarcode() {
        Product product = product(FamilyCategory.NORMAL);
        var first = inventory(product);
        authenticateWorkshop();
        var second = inventory(product);
        assertThat(second.product().barcodeValue()).isEqualTo(first.product().barcodeValue());
        assertThat(second.mutation().productBarcodeFirstUse()).isFalse();
    }
    @Test void concurrentFirstUseAcrossWorkshopsIsOfferedExactlyOnce() throws Exception {
        Product product = product(FamilyCategory.NORMAL);
        Jwt firstToken = token;
        authenticateWorkshop();
        Jwt secondToken = token;
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { authenticate(firstToken); start.await(); return inventory(product); });
            var second = executor.submit(() -> { authenticate(secondToken); start.await(); return inventory(product); });
            List<InventoryResponseDTO> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(r -> r.mutation().productBarcodeFirstUse())).hasSize(1);
            assertThat(results.stream().map(r -> r.product().barcodeValue()).distinct()).hasSize(1);
        }
    }
    @Test void specialPurchaseReturnsOnlyTheExactNewUnitsAndRefetchKeepsTheirCodes() {
        var inventory = inventory(product(FamilyCategory.SPECIAL));
        var old = purchase(inventory, 2);
        var created = purchase(inventory, 3);
        assertThat(codes(created)).hasSize(3).doesNotHaveDuplicates().doesNotContainAnyElementsOf(codes(old));
        assertThat(inventoryService.getItems(inventory.id()).stream().map(InventoryItemResponseDTO::barcodeValue))
                .containsAll(codes(old)).containsAll(codes(created)).hasSize(5);
        for (var item : created.mutation().createdItems()) {
            assertThat(items.existsById(item.id())).isTrue();
            assertThat(item.category()).isEqualTo(FamilyCategory.SPECIAL);
            assertThat(item.barcodeValue()).isEqualTo(BarcodeIdentity.item(item.id()));
        }
    }
    @Test void concurrentSpecialPurchasesNeverDuplicateOrGuessCreatedUnits() throws Exception {
        var inventory = inventory(product(FamilyCategory.SPECIAL));
        var results = parallel(4, () -> purchase(inventory, 3));
        var codes = results.stream().flatMap(r -> codes(r).stream()).toList();
        assertThat(codes).hasSize(12).doesNotHaveDuplicates();
        assertThat(inventoryService.getItems(inventory.id()).stream().map(InventoryItemResponseDTO::barcodeValue))
                .containsExactlyInAnyOrderElementsOf(codes);
    }
    @Test void twoContainersGetTwoCodesAndTheirContentsAreNotExtraCreatedLabels() {
        Product component = product(FamilyCategory.SPECIAL);
        purchase(inventory(component), 2);
        var container = inventory(product(FamilyCategory.CONTAINER));
        var first = assemble(container, component);
        var second = assemble(container, component);
        assertThat(codes(first)).hasSize(1);
        assertThat(codes(second)).hasSize(1).doesNotContainAnyElementsOf(codes(first));
        assertThat(first.mutation().createdItems().getFirst().containedItems()).hasSize(1);
        assertThat(inventoryService.getItems(container.id()).stream().map(InventoryItemResponseDTO::barcodeValue))
                .containsAll(codes(first)).containsAll(codes(second)).hasSize(2);
    }
    @Test void concurrentContainerAssemblyReturnsDistinctCommittedIdentities() throws Exception {
        Product component = product(FamilyCategory.SPECIAL);
        purchase(inventory(component), 4);
        var container = inventory(product(FamilyCategory.CONTAINER));
        var results = parallel(4, () -> assemble(container, component));
        var codes = results.stream().flatMap(r -> codes(r).stream()).toList();
        assertThat(codes).hasSize(4).doesNotHaveDuplicates();
        assertThat(inventoryService.getItems(container.id()).stream().map(InventoryItemResponseDTO::barcodeValue))
                .containsExactlyInAnyOrderElementsOf(codes);
    }
    @Test void disassemblyKeepsContainerAndReleasedItemCodesWithoutNewOutputEvents() {
        Product component = product(FamilyCategory.SPECIAL);
        var units = purchase(inventory(component), 1);
        var container = assemble(inventory(product(FamilyCategory.CONTAINER)), component);
        var physical = container.mutation().createdItems().getFirst();
        var result = transformationService.create(new CreateTransformationRequestDTO(
                physical.id(), null, null, null, null, null, null, null));
        assertThat(result.createdInventoryIds()).isEmpty();
        var released = physicalResult(result, TransformationItemRole.RELEASED);
        assertThat(released.id()).isEqualTo(units.mutation().createdItems().getFirst().id());
        assertThat(released.containerId()).isNull();
        assertThat(released.status()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertPersistedRoles(result);
        assertThat(result.items().stream().filter(i -> i.role() == TransformationItemRole.RELEASED)
                .map(i -> i.item().barcodeValue())).containsExactlyElementsOf(codes(units));
        assertThat(inventoryService.getItems(container.id()).getFirst().barcodeValue()).isEqualTo(physical.barcodeValue());
    }
    @Test void itemStatusChangesKeepTheSameIdentity() {
        var inventory = purchase(inventory(product(FamilyCategory.SPECIAL)), 1);
        var physical = inventory.mutation().createdItems().getFirst();
        InventoryItem item = items.findById(physical.id()).orElseThrow();
        item.setStatus(InventoryItemStatus.UNAVAILABLE);
        items.saveAndFlush(item);
        assertThat(inventoryService.getItems(inventory.id()).getFirst().barcodeValue()).isEqualTo(physical.barcodeValue());
    }
    @Test void exactTransformationRemainderAndNewOutputSurviveCommitAndRefetch() throws IOException {
        Product input = product(FamilyCategory.SPECIAL);
        input.setAttributes(Map.of("largo", 100, "tieneOrificio", false));
        input = products.save(input);
        Product output = products.save(Product.builder().name("Target " + unique())
                .family(input.getFamily()).sku("OUT-" + unique())
                .attributes(Map.of("largo", 40, "tieneOrificio", false)).build());
        var source = purchase(inventory(input), 2);
        var first = transform(source.mutation().createdItems().getFirst(), output);
        var outputEntry = first.items().stream().filter(i -> i.role() == TransformationItemRole.OUTPUT).findFirst().orElseThrow();
        var remainder = first.items().stream().filter(i -> i.role() == TransformationItemRole.REMAINDER).findFirst().orElseThrow();
        assertThat(first.createdInventoryIds()).contains(outputEntry.item().inventoryId(), remainder.item().inventoryId());
        assertThat(outputEntry.item().barcodeValue()).isNotEqualTo(remainder.item().barcodeValue());
        for (var entry : List.of(outputEntry, remainder)) {
            assertThat(items.existsById(entry.item().id())).isTrue();
            assertThat(inventoryService.getItems(entry.item().inventoryId()).stream()
                    .filter(item -> item.id().equals(entry.item().id())).map(InventoryItemResponseDTO::barcodeValue))
                    .containsExactly(entry.item().barcodeValue());
        }
        var history = transformationService.get(first.id());
        assertThat(history.createdInventoryIds()).isNull(); // Event is not guessed again on reads.
        assertThat(history.items().stream().map(i -> i.item().barcodeValue()))
                .contains(outputEntry.item().barcodeValue(), remainder.item().barcodeValue());
        var second = transform(source.mutation().createdItems().get(1), output);
        assertThat(second.createdInventoryIds()).doesNotContain(outputEntry.item().inventoryId());
        assertThat(second.items().stream().filter(i -> i.role() == TransformationItemRole.OUTPUT)
                .map(i -> i.item().barcodeValue())).doesNotContain(outputEntry.item().barcodeValue());
        Files.writeString(Path.of("/tmp/barcode-persisted-transformation.json"), new ObjectMapper().writeValueAsString(first));
    }
    @Test void existingOutputWithoutRemainderIsNeverMarkedNew() {
        Product input = product(FamilyCategory.SPECIAL);
        input.setAttributes(Map.of("largo", 100, "tieneOrificio", false));
        input = products.save(input);
        Product output = products.save(Product.builder().name("Existing " + unique())
                .family(input.getFamily()).sku("OUT-" + unique())
                .attributes(Map.of("largo", 80, "tieneOrificio", false)).build());
        inventory(output);
        var source = purchase(inventory(input), 1);
        var result = transform(source.mutation().createdItems().getFirst(), output);
        assertThat(result.createdInventoryIds()).isEmpty();
        assertThat(result.items()).noneMatch(i -> i.role() == TransformationItemRole.REMAINDER);
    }
    @Test void foreignWorkshopCannotReadOrTransformItemsDespiteKnowingTheBarcode() {
        var source = purchase(inventory(product(FamilyCategory.SPECIAL)), 1);
        var item = source.mutation().createdItems().getFirst();
        authenticateWorkshop();
        assertThat(inventoryService.getAll(new SearchInventoryRequestDTO(null, null, null, null, null, null))).isEmpty();
        assertThatThrownBy(() -> inventoryService.getItems(source.id())).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> transformationService.create(new CreateTransformationRequestDTO(
                item.id(), null, null, null, Map.of("largo", 1), null, null, null)))
                .isInstanceOf(EntityNotFoundException.class);
    }
    @Test void serializedDtosIncludeBarcodeValuesInExistingResponses() throws IOException {
        var normal = inventory(product(FamilyCategory.NORMAL));
        var physical = purchase(inventory(product(FamilyCategory.SPECIAL)), 2);
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readTree(json.writeValueAsString(normal)).path("product").path("barcodeValue").asText())
                .isEqualTo(normal.product().barcodeValue());
        var data = json.readTree(json.writeValueAsString(physical));
        assertThat(data.path("mutation").path("createdItems").size()).isEqualTo(2);
        assertThat(data.path("mutation").path("createdItems").get(0).path("barcodeValue").asText())
                .isEqualTo(codes(physical).getFirst());
        Files.writeString(Path.of("/tmp/barcode-persisted-inventory.json"), json.writeValueAsString(normal));
    }
    @Test void invalidStockEntryCannotReturnLabelsOrLeavePhysicalUnits() {
        var inventory = inventory(product(FamilyCategory.SPECIAL));
        assertThatThrownBy(() -> inventoryService.addStock(inventory.id(), new AddStockRequestDTO(
                3, UUID.randomUUID(), AcquisitionType.NATIONAL, BigDecimal.ONE, null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(inventoryService.getItems(inventory.id())).isEmpty();
    }
    @Test void modelLessCustomOutputsReuseCommittedInventory() {
        assertModelLessOutputReuse(null);
    }

    @Test void modelLessNamedOutputsReuseCommittedInventory() {
        assertModelLessOutputReuse("Named barcode output " + unique());
    }

    private void assertModelLessOutputReuse(String outputName) {
        Product input = product(FamilyCategory.SPECIAL);
        Family family = input.getFamily();
        family.addAttribute(Attribute.builder()
                .family(family).name("largo")
                .dataType(AttributeDataType.NUMBER)
                .isRequired(true).build());
        family.addAttribute(Attribute.builder()
                .family(family).name("tieneOrificio")
                .dataType(AttributeDataType.BOOLEAN)
                .isRequired(true).build());
        families.saveAndFlush(family);
        input.setAttributes(Map.of("largo", 100, "tieneOrificio", false));
        input = products.save(input);
        var source = purchase(inventory(input), 2);
        var first = transformationService.create(new CreateTransformationRequestDTO(
                source.mutation().createdItems().get(0).id(), null, null, null,
                Map.of("largo", 40), null, null, outputName));
        var second = transformationService.create(new CreateTransformationRequestDTO(
                source.mutation().createdItems().get(1).id(), null, null, null,
                Map.of("largo", 40), null, null, outputName));
        assertThat(first.createdInventoryIds()).hasSize(2);
        assertThat(second.createdInventoryIds()).isEmpty();
        for (var role : List.of(TransformationItemRole.OUTPUT, TransformationItemRole.REMAINDER)) {
            var original = first.items().stream().filter(e -> e.role() == role)
                    .findFirst().orElseThrow().item();
            var repeated = second.items().stream().filter(e -> e.role() == role)
                    .findFirst().orElseThrow().item();
            assertThat(repeated.productId()).isEqualTo(original.productId());
            assertThat(repeated.inventoryId()).isEqualTo(original.inventoryId());
            assertThat(repeated.id()).isNotEqualTo(original.id());
            assertThat(repeated.barcodeValue()).isNotEqualTo(original.barcodeValue());
            assertThat(inventoryService.getItems(original.inventoryId()).stream()
                    .map(InventoryItemResponseDTO::barcodeValue))
                    .contains(original.barcodeValue(), repeated.barcodeValue());
        }
    }


    @Test void materialLisoToLeafCreatesNewPhysicalIdentityInNewInventory() {
        assertMaterialLisoPhysicalIdentity(false, false);
    }

    @Test void existingLeafInventoryNeverReusesAnOldAvailablePhysicalItem() {
        assertMaterialLisoPhysicalIdentity(true, false);
    }

    @Test void materialLisoOutputAndRemainderStayDistinctAcrossRepeatedTransformations() {
        assertMaterialLisoPhysicalIdentity(false, true);
    }

    /** Calls the transactional service WITHOUT a test transaction: JDBC observes committed rows. */
    private void assertMaterialLisoPhysicalIdentity(boolean existingInventory, boolean withRemainder) {
        Family material = families.findAll().stream().filter(f -> f.getCode().equals("ML")).findFirst().orElseThrow();
        Family leafFamily = families.findAll().stream().filter(f -> f.getCode().equals("HR")).findFirst().orElseThrow();
        var brand = brands.save(com.cns.spring_house.brand.entity.Brand.builder().name("Identity " + unique()).logoKey("brands/identity-qa.png").build());
        var model = models.save(com.cns.spring_house.model.entity.Model.builder()
                .brand(brand).name("Identity " + unique()).yearFrom(2020).yearTo(2022).build());
        Product materialProduct = products.save(Product.builder().family(material).name("Material Liso " + unique())
                .sku("ML-" + unique()).attributes(Map.of("largo", 1200, "tieneOrificio", false, "cantidadColochos", 0)).build());
        Product leaf = products.save(Product.builder().family(leafFamily).model(model).name("Hoja de Resorte " + unique())
                .sku("HR-" + unique()).attributes(Map.of("largo", withRemainder ? 500 : 900,
                        "tieneOrificio", true, "largoIzquierdo", withRemainder ? 250 : 450,
                        "largoDerecho", withRemainder ? 250 : 450, "cantidadColochos", 2)).build());
        var source = purchase(inventory(materialProduct), 2);
        InventoryItemResponseDTO oldItem = existingInventory
                ? purchase(inventory(leaf), 1).mutation().createdItems().getFirst() : null;
        long productCount = count("SELECT count(*) FROM product");
        long inventoryCount = count("SELECT count(*) FROM inventory WHERE workshop_id = ?", workshop.getId());
        long itemCount = count("SELECT count(*) FROM inventory_item i JOIN inventory v ON v.id = i.inventory_id WHERE v.workshop_id = ?", workshop.getId());
        long allocations = count("SELECT count(*) FROM sale_detail_cost_allocation");
        InventoryItemResponseDTO previousOutput = null;
        InventoryItemResponseDTO previousRemainder = null;
        for (int index = 0; index < 2; index++) {
            var input = source.mutation().createdItems().get(index);
            var result = transformationService.create(new CreateTransformationRequestDTO(input.id(), null,
                    leaf.getId(), null, null, null, withRemainder ? 0 : null, null));
            var output = physicalResult(result, TransformationItemRole.OUTPUT);
            var historical = physicalResult(result, TransformationItemRole.INPUT);
            assertThat(output.id()).isNotEqualTo(input.id());
            assertThat(output.barcodeValue()).isNotEqualTo(input.barcodeValue()).isEqualTo(BarcodeIdentity.item(output.id()));
            assertThat(output.productId()).isEqualTo(leaf.getId());
            assertThat(output.category()).isEqualTo(FamilyCategory.SPECIAL);
            assertThat(output.attributes()).containsEntry("cantidadColochos", 2);
            assertThat(jdbc.queryForObject("SELECT model_id FROM product WHERE id = ?", UUID.class, output.productId())).isEqualTo(model.getId());
            assertThat(historical.id()).isEqualTo(input.id());
            assertThat(historical.barcodeValue()).isEqualTo(input.barcodeValue());
            assertThat(historical.status()).isEqualTo(InventoryItemStatus.CONSUMED);
            assertThat(inventoryService.getItems(source.id()).stream().filter(i -> i.id().equals(input.id()))
                    .map(InventoryItemResponseDTO::barcodeValue)).containsExactly(input.barcodeValue());
            assertPersistedRoles(result);
            assertThat(count("SELECT count(*) FROM work_result WHERE item_id = ?", output.id())).isEqualTo(1);
            assertThat(count("SELECT stock FROM inventory WHERE id = ?", source.id())).isEqualTo(1 - index);
            assertThat(count("SELECT quantity_remaining FROM inventory_acquisition WHERE inventory_id = ?", source.id())).isEqualTo(1 - index);
            assertThat(jdbc.queryForObject("SELECT unit_cost FROM inventory_acquisition WHERE inventory_id = ?", BigDecimal.class, source.id()))
                    .isEqualByComparingTo(BigDecimal.ONE);
            if (oldItem != null) {
                assertThat(output.inventoryId()).isEqualTo(oldItem.inventoryId());
                assertThat(output.id()).isNotEqualTo(oldItem.id());
                assertThat(output.barcodeValue()).isNotEqualTo(oldItem.barcodeValue());
                assertThat(jdbc.queryForObject("SELECT status FROM inventory_item WHERE id = ?", String.class, oldItem.id())).isEqualTo("AVAILABLE");
            }
            if (previousOutput != null) {
                assertThat(output.productId()).isEqualTo(previousOutput.productId());
                assertThat(output.inventoryId()).isEqualTo(previousOutput.inventoryId());
                assertThat(output.id()).isNotEqualTo(previousOutput.id());
                assertThat(output.barcodeValue()).isNotEqualTo(previousOutput.barcodeValue());
                assertThat(result.createdInventoryIds()).isEmpty();
            } else if (existingInventory) {
                assertThat(result.createdInventoryIds()).isEmpty();
            } else {
                assertThat(result.createdInventoryIds()).contains(output.inventoryId()).hasSize(withRemainder ? 2 : 1);
            }
            assertProduction(output, index + 1, (oldItem == null ? 0 : 1) + index + 1);
            if (withRemainder) {
                var remainder = physicalResult(result, TransformationItemRole.REMAINDER);
                assertThat(List.of(input.id(), output.id(), remainder.id())).doesNotHaveDuplicates();
                assertThat(List.of(input.barcodeValue(), output.barcodeValue(), remainder.barcodeValue())).doesNotHaveDuplicates();
                assertThat(jdbc.queryForObject("SELECT family_id FROM product WHERE id = ?", UUID.class, remainder.productId())).isEqualTo(material.getId());
                assertThat(jdbc.queryForObject("SELECT model_id FROM product WHERE id = ?", UUID.class, remainder.productId())).isNull();
                assertThat(remainder.attributes()).containsEntry("cantidadColochos", 0);
                assertThat(((Number) remainder.attributes().get("largo")).intValue()).isEqualTo(700);
                assertThat(count("SELECT count(*) FROM work_result WHERE item_id = ?", remainder.id())).isZero();
                if (previousRemainder != null) {
                    assertThat(remainder.inventoryId()).isEqualTo(previousRemainder.inventoryId());
                    assertThat(remainder.productId()).isEqualTo(previousRemainder.productId());
                    assertThat(remainder.id()).isNotEqualTo(previousRemainder.id());
                }
                assertProduction(remainder, index + 1, index + 1);
                previousRemainder = remainder;
            } else {
                assertThat(result.items()).noneMatch(e -> e.role() == TransformationItemRole.REMAINDER);
            }
            assertThat(count("SELECT count(*) FROM inventory WHERE workshop_id = ?", workshop.getId()))
                    .isEqualTo(inventoryCount + (existingInventory ? 0 : 1) + (withRemainder ? 1 : 0));
            assertThat(count("SELECT count(*) FROM inventory_item i JOIN inventory v ON v.id = i.inventory_id WHERE v.workshop_id = ?", workshop.getId()))
                    .isEqualTo(itemCount + (index + 1) * (withRemainder ? 2 : 1));
            assertThat(count("SELECT count(*) FROM product")).isEqualTo(productCount + (withRemainder ? 1 : 0));
            assertThat(count("SELECT count(*) FROM sale_detail_cost_allocation")).isEqualTo(allocations);
            previousOutput = output;
        }
    }

    private void assertProduction(InventoryItemResponseDTO item, int productionCount, int stock) {
        assertThat(count("SELECT stock FROM inventory WHERE id = ?", item.inventoryId())).isEqualTo(stock);
        assertThat(count("SELECT count(*) FROM inventory_item WHERE inventory_id = ? AND status = 'AVAILABLE'", item.inventoryId())).isEqualTo(stock);
        assertThat(count("SELECT count(*) FROM inventory_acquisition WHERE inventory_id = ? AND origin = 'PRODUCTION'", item.inventoryId())).isEqualTo(productionCount);
        assertThat(count("SELECT count(*) FROM inventory_acquisition WHERE inventory_id = ? AND origin = 'PRODUCTION' AND quantity_received = 1 AND quantity_remaining = 1 AND unit_cost IS NULL AND supplier_id IS NULL", item.inventoryId())).isEqualTo(productionCount);
    }

    private void assertPersistedRoles(TransformationResponseDTO response) {
        var rows = jdbc.queryForList("SELECT t.role, t.item_id, i.inventory_id, v.workshop_id FROM transformation_item t JOIN inventory_item i ON i.id = t.item_id JOIN inventory v ON v.id = i.inventory_id WHERE t.transformation_id = ?", response.id());
        assertThat(rows).hasSize(response.items().size());
        assertThat(rows.stream().map(row -> (UUID) row.get("item_id"))).doesNotHaveDuplicates();
        var refetched = transformationService.get(response.id());
        for (var entry : response.items()) {
            assertThat(rows).anySatisfy(row -> {
                assertThat(row.get("role")).isEqualTo(entry.role().name());
                assertThat(row.get("item_id")).isEqualTo(entry.item().id());
                assertThat(row.get("inventory_id")).isEqualTo(entry.item().inventoryId());
                assertThat(row.get("workshop_id")).isEqualTo(workshop.getId());
            });
            assertThat(refetched.items()).anySatisfy(saved -> {
                assertThat(saved.role()).isEqualTo(entry.role());
                assertThat(saved.item().id()).isEqualTo(entry.item().id());
                assertThat(saved.item().barcodeValue()).isEqualTo(entry.item().barcodeValue());
            });
            assertThat(inventoryService.getItems(entry.item().inventoryId()).stream().filter(i -> i.id().equals(entry.item().id()))
                    .map(InventoryItemResponseDTO::barcodeValue)).containsExactly(entry.item().barcodeValue());
        }
    }

    private InventoryItemResponseDTO physicalResult(TransformationResponseDTO response, TransformationItemRole role) {
        return response.items().stream().filter(e -> e.role() == role).findFirst().orElseThrow().item();
    }

    private long count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private TransformationResponseDTO transform(InventoryItemResponseDTO input, Product output) {
        return transformationService.create(new CreateTransformationRequestDTO(
                input.id(), null, output.getId(), null, null, null, null, null));
    }
    private List<InventoryResponseDTO> parallel(int count, Callable<InventoryResponseDTO> action) throws Exception {
        Jwt currentToken = token;
        CyclicBarrier start = new CyclicBarrier(count);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<InventoryResponseDTO>>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> {
                authenticate(currentToken);
                try { start.await(); return action.call(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            var results = new ArrayList<InventoryResponseDTO>();
            for (var future : futures) results.add(future.get());
            return results;
        }
    }
}
