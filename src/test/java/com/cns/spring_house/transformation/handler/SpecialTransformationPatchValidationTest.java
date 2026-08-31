package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Contrato de `outputAttributes` como PATCH. Es el camino principal de negocio:
 * el cliente manda solo las características que cambian y el backend construye
 * la pieza sobre las de la entrada.
 *
 * El cliente NO es una fuente fiable: cada clave tiene que existir en
 * `Family.attributes` y cada valor tiene que respetar su `AttributeDataType`,
 * de modo que ninguna petición pueda meter una propiedad inventada ni un tipo
 * equivocado en el JSONB de un Product nuevo.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationPatchValidationTest {

    private static final String CURLS = "cantidadColochos";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private WorkshopRepository workshopRepository;

    @Mock
    private InventoryItemRepository itemRepository;

    @Mock
    private ObjectMapper objectMapper;

    /**
     * El libro de lotes: toda entrada o salida de stock deja ahora su lote. Se
     * simula porque estas pruebas miden la transformacion, no el FIFO.
     */
    @Mock
    private InventoryLotService lotService;

    @InjectMocks
    private SpecialTransformationHandler handler;

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder().id(workshopId).build();
    private final Model model = Model.builder().id(UUID.randomUUID()).build();

    /** Los atributos reales de HOJA DE RESORTE. */
    private final Family family = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER, true),
            attribute("ancho", AttributeDataType.NUMBER, true),
            attribute("grosor", AttributeDataType.NUMBER, true),
            attribute("numeroHoja", AttributeDataType.NUMBER, true),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN, true),
            attribute("posicionEje", AttributeDataType.STRING, false),
            attribute(CURLS, AttributeDataType.NUMBER, false));

    @BeforeEach
    void setUpAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @BeforeEach
    void setUpRepositories() {
        lenient().when(itemRepository.save(any(InventoryItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(inventoryRepository
                .findByProductIdAndWorkshopId(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(workshopRepository.findById(workshopId))
                .thenReturn(Optional.of(workshop));
        lenient().when(productRepository.findTargetProduct(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Claves declaradas por la familia ───────────────────────────────

    @Test
    void anAttributeTheFamilyDoesNotDeclareIsRejected() {
        InventoryItem inputItem = fullSheet();

        assertThatThrownBy(() -> transform(inputItem,
                request(Map.of("colorPintura", "ROJO"), 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La característica 'colorPintura' no está declarada para la familia 'HOJA DE RESORTE'");

        assertNothingWasMaterialised(inputItem);
    }

    /** Una clave desconocida no se cuela ni acompañada de otras válidas. */
    @Test
    void oneUndeclaredAttributeInvalidatesTheWholePatch() {
        InventoryItem inputItem = fullSheet();
        Map<String, Object> patch = new HashMap<>();
        patch.put("largo", 900);
        patch.put("colorPintura", "ROJO");

        assertThatThrownBy(() -> transform(inputItem, request(patch, 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("colorPintura");

        assertNothingWasMaterialised(inputItem);
    }

    // ── Grafía canónica ────────────────────────────────────────────────

    @Test
    void aRequestedKeyIsStoredWithTheSpellingTheFamilyDeclares() {
        InventoryItem inputItem = fullSheet();

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(Map.of("LARGO", 900), 0, null)));

        assertThat(output).containsEntry("largo", 900);
        assertThat(output).doesNotContainKey("LARGO");
    }

    /** Ni siquiera cuando la entrada guardó la clave con otra grafía. */
    @Test
    void theCanonicalSpellingReplacesTheOneStoredInTheInput() {
        InventoryItem inputItem = itemOf(attributes(
                "LARGO", 1200, "ancho", 80, "grosor", 10,
                "numeroHoja", 3, "tieneOrificio", false));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(Map.of("largo", 900), 0, null)));

        assertThat(output).containsEntry("largo", 900);
        assertThat(output).doesNotContainKey("LARGO");
    }

    // ── Tipos ──────────────────────────────────────────────────────────

    static Stream<Arguments> validValues() {
        return Stream.of(
                Arguments.of("NUMBER entero", "largo", 900),
                Arguments.of("NUMBER largo", "largo", 900L),
                Arguments.of("NUMBER decimal", "largo", 900.5),
                Arguments.of("NUMBER BigDecimal", "largo", new BigDecimal("900.5")),
                Arguments.of("BOOLEAN", "tieneOrificio", true),
                Arguments.of("STRING", "posicionEje", "VERTICAL"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validValues")
    void aValueOfTheDeclaredTypeIsAccepted(
            String description, String key, Object value) {
        InventoryItem inputItem = fullSheet();

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(Map.of(key, value), 0, null)));

        assertThat(output).containsEntry(key, value);
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("NUMBER como texto", "largo", "900", "NUMBER"),
                Arguments.of("NUMBER como booleano", "largo", true, "NUMBER"),
                Arguments.of("BOOLEAN como texto", "tieneOrificio", "true", "BOOLEAN"),
                Arguments.of("BOOLEAN como numero", "tieneOrificio", 1, "BOOLEAN"),
                Arguments.of("STRING como numero", "posicionEje", 3, "STRING"),
                Arguments.of("STRING como booleano", "posicionEje", false, "STRING"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidValues")
    void aValueOfAnotherTypeIsRejected(
            String description, String key, Object value, String expectedType) {
        InventoryItem inputItem = fullSheet();

        assertThatThrownBy(() -> transform(
                inputItem, request(Map.of(key, value), 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La característica '" + key + "' debe ser de tipo " + expectedType);

        assertNothingWasMaterialised(inputItem);
    }

    /**
     * Un `null` explícito se rechaza como cualquier otro tipo inválido: es la
     * regla de `AttributeDataType.isValid`, la misma del alta de productos. Este
     * PATCH no elimina características, solo añade o sustituye.
     */
    @Test
    void anExplicitNullIsRejectedInsteadOfDeletingTheAttribute() {
        InventoryItem inputItem = fullSheet();
        Map<String, Object> patch = new HashMap<>();
        patch.put("posicionEje", null);

        assertThatThrownBy(() -> transform(inputItem, request(patch, 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("posicionEje");

        assertNothingWasMaterialised(inputItem);
    }

    /** Y por lo mismo el PATCH no puede dejar sin un obligatorio a la salida. */
    @Test
    void aRequiredAttributeCannotBeRemovedByThePatch() {
        InventoryItem inputItem = fullSheet();
        Map<String, Object> patch = new HashMap<>();
        patch.put("largo", null);

        assertThatThrownBy(() -> transform(inputItem, request(patch, 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("largo");

        assertNothingWasMaterialised(inputItem);
    }

    // ── cantidadColochos reservado ─────────────────────────────────────

    @Test
    void theCurlCountIsRejectedInsideTheAttributes() {
        InventoryItem inputItem = fullSheet();

        assertThatThrownBy(() -> transform(
                inputItem, request(Map.of(CURLS, 2), 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La cantidad de colochos se indica en outputCurlCount, no dentro de outputAttributes");

        assertNothingWasMaterialised(inputItem);
    }

    @Test
    void theCurlCountIsRejectedWrittenInAnotherCase() {
        InventoryItem inputItem = fullSheet();

        assertThatThrownBy(() -> transform(
                inputItem, request(Map.of("CANTIDADCOLOCHOS", 2), 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La cantidad de colochos se indica en outputCurlCount, no dentro de outputAttributes");
    }

    // ── PATCH parcial ──────────────────────────────────────────────────

    /** El caso que manda el frontend: una sola característica copiada. */
    @Test
    void onlyTheRequestedAttributeChangesAndTheRestIsKept() {
        InventoryItem inputItem = fullSheet();

        Map<String, Object> output = outputAttributesOf(transform(
                inputItem, request(Map.of("posicionEje", "VERTICAL"), 2, null)));

        assertThat(output).isEqualTo(attributes(
                "largo", 1200,
                "ancho", 80,
                "grosor", 10,
                "numeroHoja", 3,
                "tieneOrificio", false,
                "posicionEje", "VERTICAL",
                CURLS, 2));
    }

    /** Los obligatorios no hay que reenviarlos: se conservan de la entrada. */
    @Test
    void requiredAttributesDoNotNeedToBeResent() {
        InventoryItem inputItem = fullSheet();

        Map<String, Object> output = outputAttributesOf(transform(
                inputItem, request(Map.of("largo", 900), 0, null)));

        assertThat(output)
                .containsEntry("largo", 900)
                .containsEntry("ancho", 80)
                .containsEntry("grosor", 10)
                .containsEntry("numeroHoja", 3)
                .containsEntry("tieneOrificio", false);
    }

    // ── Familia y modelo ───────────────────────────────────────────────

    /**
     * El backend no sabe nada del producto de referencia que el operador miró en
     * pantalla: solo recibe el PATCH. Familia y modelo salen siempre de la
     * entrada, aunque el valor copiado venga de una pieza de otro modelo.
     */
    @Test
    void theResultKeepsTheFamilyAndModelOfTheInput() {
        InventoryItem inputItem = fullSheet();

        transform(inputItem, request(Map.of("posicionEje", "VERTICAL"), 2, null));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertThat(saved.getValue().getFamily()).isSameAs(family);
        assertThat(saved.getValue().getModel()).isSameAs(model);
    }

    // ── Solo colochos ──────────────────────────────────────────────────

    @Test
    void aCurlOnlyTransformationStillWorksWithoutAttributes() {
        InventoryItem inputItem = fullSheet();

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(null, 2, null)));

        assertThat(output).containsEntry(CURLS, 2);
        assertThat(output).containsEntry("largo", 1200);
    }

    @Test
    void aPieceIdenticalToTheInputIsStillRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "ancho", 80, "grosor", 10,
                "numeroHoja", 3, "tieneOrificio", false, CURLS, 2));

        assertThatThrownBy(() -> transform(inputItem, request(null, 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La pieza resultante debe diferenciarse del producto de entrada");

        assertNothingWasMaterialised(inputItem);
    }

    // ── Atomicidad ─────────────────────────────────────────────────────

    /**
     * Un PATCH inválido se rechaza antes de tocar el catálogo, el inventario y
     * la unidad de entrada, así que la transacción no tiene nada que revertir a
     * medias.
     */
    @Test
    void anInvalidPatchLeavesNoPartialEntities() {
        InventoryItem inputItem = fullSheet();
        Transformation transformation = Transformation.builder().build();
        int stockBefore = inputItem.getInventory().getStock();

        assertThatThrownBy(() -> handler.generate(transformation, inputItem,
                request(Map.of("colorPintura", "ROJO"), 0, null)))
                .isInstanceOf(ValidationException.class);

        assertThat(transformation.getItems()).isEmpty();
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(inputItem.getInventory().getStock()).isEqualTo(stockBefore);
        verify(productRepository, never()).save(any(Product.class));
        verify(productRepository, never()).findTargetProduct(any(), any(), any());
        verify(inventoryRepository, never()).save(any(Inventory.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
    }

    // ── Auxiliares ─────────────────────────────────────────────────────

    private static Attribute attribute(
            String name, AttributeDataType dataType, boolean isRequired) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(isRequired)
                .build();
    }

    private static Family familyDeclaring(Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .name("HOJA DE RESORTE")
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>(List.of(declared)))
                .build();
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put(
                    (String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    /** Entrada realista: una hoja con todas sus características. */
    private InventoryItem fullSheet() {
        return itemOf(attributes(
                "largo", 1200, "ancho", 80, "grosor", 10,
                "numeroHoja", 3, "tieneOrificio", false));
    }

    private InventoryItem itemOf(Map<String, Object> productAttributes) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(Product.builder()
                        .id(UUID.randomUUID())
                        .family(family)
                        .model(model)
                        .attributes(productAttributes)
                        .build())
                .stock(5)
                .build();
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(item);
        return item;
    }

    private CreateTransformationRequestDTO request(
            Map<String, Object> outputAttributes,
            Integer outputCurlCount,
            Integer remainderCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, null, null,
                outputAttributes, outputCurlCount, remainderCurlCount, null);
    }

    private Transformation transform(
            InventoryItem inputItem, CreateTransformationRequestDTO requestDTO) {
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem, requestDTO);
        return transformation;
    }

    private Map<String, Object> outputAttributesOf(Transformation transformation) {
        return transformation.getItems()
                .stream()
                .filter(item -> item.getRole() == TransformationItemRole.OUTPUT)
                .findFirst()
                .map(TransformationItem::getItem)
                .orElseThrow()
                .getInventory()
                .getProduct()
                .getAttributes();
    }

    private void assertNothingWasMaterialised(InventoryItem inputItem) {
        verify(productRepository, never()).save(any(Product.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(inputItem.getInventory().getStock()).isEqualTo(5);
    }

}
