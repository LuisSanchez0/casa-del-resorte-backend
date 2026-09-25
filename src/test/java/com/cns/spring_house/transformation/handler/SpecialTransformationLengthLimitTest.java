package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * El largo resultante de una transformacion no puede superar el de la pieza que
 * se transforma.
 *
 * Transformar es modificar una pieza fisica existente: se corta o se rectifica,
 * nunca se alarga. Conservar el largo o reducirlo son transformaciones validas;
 * aumentarlo describiria una pieza que no puede salir de esa.
 *
 * La regla se activa por atributo declarado, no por familia: aqui la familia
 * declara {@code largo} numerico y por eso aplica. La ultima prueba usa una
 * familia que no lo declara para fijar que ahi no interviene.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationLengthLimitTest {

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

    @Mock
    private InventoryLotService lotService;

    @InjectMocks
    private SpecialTransformationHandler handler;

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder().id(workshopId).build();
    private final Model model = Model.builder().id(UUID.randomUUID()).build();

    /** Declara `largo` numerico: la regla aplica. */
    private final Family family = familyDeclaring(
            "FAMILIA CON LARGO",
            attribute("largo", AttributeDataType.NUMBER, true),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN, false),
            attribute("largoIzquierdo", AttributeDataType.NUMBER, false),
            attribute("largoDerecho", AttributeDataType.NUMBER, false),
            attribute("posicionEje", AttributeDataType.STRING, false));

    /** No declara `largo`: la regla no interviene. */
    private final Family familyWithoutLength = familyDeclaring(
            "FAMILIA SIN LARGO",
            attribute("posicionEje", AttributeDataType.STRING, false));

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
        lenient().when(inventoryRepository.findByProductIdAndWorkshopId(any(), any()))
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

    // ── Transformacion personalizada ─────────────────────────────────

    @Test
    void aCustomResultShorterThanTheInputIsAllowed() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, custom(Map.of("largo", 180))));

        assertThat(output).containsEntry("largo", 180);
    }

    /**
     * La igualdad se permite: cambiar los colochos o abrir un orificio no altera
     * el largo, y esas siguen siendo transformaciones legitimas.
     */
    @Test
    void aCustomResultEqualToTheInputIsAllowed() {
        InventoryItem inputItem = itemOf(family, attributes(
                "largo", 200, "posicionEje", "CENTRO"));

        Map<String, Object> output = outputAttributesOf(transform(
                inputItem, custom(Map.of("largo", 200, "posicionEje", "DERECHA"))));

        assertThat(output).containsEntry("largo", 200);
    }

    @Test
    void aCustomResultLongerThanTheInputIsRejected() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("largo", 250))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo resultante no puede ser mayor al largo original del producto (200).");

        assertNothingWasMaterialised(inputItem);
    }

    /** El error viaja atado a la clave canonica, para poder senalar el campo. */
    @Test
    void theRejectionCarriesTheCanonicalLengthFieldError() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("largo", 250))))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).getErrors())
                        .containsEntry(
                                "largo",
                                "El largo resultante no puede ser mayor al largo original del producto (200)."));
    }

    /**
     * La familia escribe el atributo como quiera. El error se ata a SU grafia,
     * no a una constante del codigo.
     */
    @Test
    void theFieldErrorUsesTheSpellingDeclaredByTheFamily() {
        Family upperCase = familyDeclaring(
                "FAMILIA EN MAYUSCULAS",
                attribute("LARGO", AttributeDataType.NUMBER, true));
        InventoryItem inputItem = itemOf(upperCase, attributes("LARGO", 200));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("LARGO", 250))))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).getErrors())
                        .containsKey("LARGO"));
    }

    // ── Decimales ────────────────────────────────────────────────────

    /**
     * La comparacion es {@link BigDecimal}: un exceso en el segundo decimal se
     * detecta igual que uno de 50 unidades.
     */
    @Test
    void aDecimalExcessIsRejected() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200.5));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("largo", 200.51))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo resultante no puede ser mayor al largo original del producto (200.5).");
    }

    @Test
    void aDecimalReductionIsAllowed() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200.5));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, custom(Map.of("largo", 200.49))));

        assertThat(output).containsEntry("largo", 200.49);
    }

    /**
     * Mismo valor con distinta escala: {@code 200} y {@code 200.00} son el mismo
     * largo. `compareTo` lo trata como igualdad; `equals` no lo haria.
     */
    @Test
    void theSameLengthWrittenWithAnotherScaleIsStillEqual() {
        InventoryItem inputItem = itemOf(family, attributes("largo", new BigDecimal("200.00")));

        assertThatCode(() -> transform(inputItem, custom(Map.of("largo", 200))))
                .doesNotThrowAnyException();
    }

    // ── Copia desde un producto de referencia ────────────────────────

    @Test
    void aReferenceShorterThanTheInputIsAllowed() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));
        Product reference = productOf(family, attributes("largo", 180));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, withReference(reference)));

        assertThat(output).containsEntry("largo", 180);
    }

    @Test
    void aReferenceEqualToTheInputIsAllowed() {
        InventoryItem inputItem = itemOf(family, attributes(
                "largo", 200, "posicionEje", "CENTRO"));
        Product reference = productOf(family, attributes(
                "largo", 200, "posicionEje", "DERECHA"));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, withReference(reference)));

        assertThat(output).containsEntry("largo", 200);
    }

    /**
     * El caso que motiva la regla en el modo copia: la referencia describe una
     * pieza mas larga que la que se tiene delante. No se trunca a 200 en
     * silencio; se rechaza, porque el operador pidio algo que no se puede
     * fabricar con ese material.
     */
    @Test
    void aReferenceLongerThanTheInputIsRejected() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));
        Product reference = productOf(family, attributes("largo", 250));

        assertThatThrownBy(() -> transform(inputItem, withReference(reference)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo resultante no puede ser mayor al largo original del producto (200).");

        assertNothingWasMaterialised(inputItem);
    }

    /**
     * Un producto de catalogo mas largo no puede colarse por ser reutilizable: la
     * regla corre antes de resolver o crear el producto resultante.
     */
    @Test
    void anExistingCatalogProductCannotBypassTheRule() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));
        Product reference = productOf(family, attributes("largo", 250));
        lenient().when(productRepository.findTargetProduct(any(), any(), any()))
                .thenReturn(Optional.of(reference));

        assertThatThrownBy(() -> transform(inputItem, withReference(reference)))
                .isInstanceOf(ValidationException.class);

        assertNothingWasMaterialised(inputItem);
    }

    // ── Interaccion con las medidas de orificio ──────────────────────

    /**
     * Las dos reglas deben cumplirse. Cuando el largo ni siquiera cabe en la
     * entrada, ese es el problema que se cuenta: decir que los lados no suman un
     * largo imposible mandaria al operador a corregir lo que no falla.
     */
    @Test
    void theLengthRuleIsReportedBeforeTheHoleRule() {
        InventoryItem inputItem = itemOf(family, attributes(
                "largo", 200, "tieneOrificio", true,
                "largoIzquierdo", 100, "largoDerecho", 100));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of(
                "largo", 250, "largoIzquierdo", 100, "largoDerecho", 100))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo resultante no puede ser mayor al largo original del producto (200).");
    }

    /** Un largo valido deja que la regla de orificio siga decidiendo. */
    @Test
    void aValidLengthStillHasToSatisfyTheHoleRule() {
        InventoryItem inputItem = itemOf(family, attributes(
                "largo", 200, "tieneOrificio", true,
                "largoIzquierdo", 100, "largoDerecho", 100));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of(
                "largo", 180, "largoIzquierdo", 100, "largoDerecho", 100))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");
    }

    @Test
    void aShorterLengthWithMatchingSidesIsAllowed() {
        InventoryItem inputItem = itemOf(family, attributes(
                "largo", 200, "tieneOrificio", true,
                "largoIzquierdo", 100, "largoDerecho", 100));

        Map<String, Object> output = outputAttributesOf(transform(inputItem, custom(Map.of(
                "largo", 180, "largoIzquierdo", 90, "largoDerecho", 90))));

        assertThat(output)
                .containsEntry("largo", 180)
                .containsEntry("largoIzquierdo", 90)
                .containsEntry("largoDerecho", 90);
    }

    // ── Familias sin `largo` ─────────────────────────────────────────

    /**
     * Una familia que no declara `largo` no hereda la regla: su transformacion se
     * comporta exactamente como antes.
     */
    @Test
    void aFamilyWithoutLengthIsUnaffected() {
        InventoryItem inputItem = itemOf(familyWithoutLength, attributes(
                "posicionEje", "CENTRO"));

        assertThatCode(() -> transform(
                inputItem, custom(Map.of("posicionEje", "DERECHA"))))
                .doesNotThrowAnyException();
    }

    /**
     * Ni siquiera cuando el producto arrastra un `largo` en su JSONB: lo que
     * activa la regla es lo que la familia declara, no lo que el mapa contenga.
     */
    @Test
    void aFamilyWithoutLengthIgnoresALengthValueLeftInTheProduct() {
        InventoryItem inputItem = itemOf(familyWithoutLength, attributes(
                "largo", 200, "posicionEje", "CENTRO"));

        assertThatCode(() -> transform(
                inputItem, custom(Map.of("posicionEje", "DERECHA"))))
                .doesNotThrowAnyException();
    }

    // ── Entrada estructuralmente inconsistente ───────────────────────

    /**
     * La familia declara `largo` numerico pero la pieza de entrada no lo tiene.
     * No hay techo contra el que comparar, asi que la transformacion se bloquea
     * en lugar de saltarse la regla en silencio.
     */
    @Test
    void anInputWithoutTheDeclaredLengthIsRejected() {
        InventoryItem inputItem = itemOf(family, attributes("posicionEje", "CENTRO"));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("posicionEje", "DERECHA"))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El producto de entrada no tiene un largo valido y no se puede transformar.");

        assertNothingWasMaterialised(inputItem);
    }

    @Test
    void anInputWithANonNumericLengthIsRejected() {
        InventoryItem inputItem = itemOf(family, attributes("largo", "doscientos"));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("posicionEje", "DERECHA"))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El producto de entrada no tiene un largo valido y no se puede transformar.");

        assertNothingWasMaterialised(inputItem);
    }

    // ── Nada se materializa si la regla falla ────────────────────────

    @Test
    void anInvalidLengthLeavesNoPartialEntities() {
        InventoryItem inputItem = itemOf(family, attributes("largo", 200));
        Transformation transformation = Transformation.builder().build();
        int stockBefore = inputItem.getInventory().getStock();

        assertThatThrownBy(() -> handler.generate(
                transformation, inputItem, custom(Map.of("largo", 250))))
                .isInstanceOf(ValidationException.class);

        assertThat(transformation.getItems()).isEmpty();
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(inputItem.getInventory().getStock()).isEqualTo(stockBefore);
        verify(productRepository, never()).save(any(Product.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
        verify(inventoryRepository, never()).save(any(Inventory.class));
    }

    // ── Auxiliares ───────────────────────────────────────────────────

    private static Attribute attribute(
            String name, AttributeDataType dataType, boolean isRequired) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(isRequired)
                .build();
    }

    private static Family familyDeclaring(String name, Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .name(name)
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>(List.of(declared)))
                .build();
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    private Product productOf(Family productFamily, Map<String, Object> productAttributes) {
        return Product.builder()
                .id(UUID.randomUUID())
                .family(productFamily)
                .model(model)
                .attributes(productAttributes)
                .build();
    }

    private InventoryItem itemOf(Family productFamily, Map<String, Object> productAttributes) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(productOf(productFamily, productAttributes))
                .stock(5)
                .build();
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(item);
        return item;
    }

    private CreateTransformationRequestDTO custom(Map<String, Object> outputAttributes) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, null, null,
                outputAttributes, null, null, null);
    }

    /** Modo copia: el backend recibe el id del producto de referencia. */
    private CreateTransformationRequestDTO withReference(Product reference) {
        lenient().when(productRepository.findById(reference.getId()))
                .thenReturn(Optional.of(reference));
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, reference.getId(), null,
                null, null, null, null);
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
