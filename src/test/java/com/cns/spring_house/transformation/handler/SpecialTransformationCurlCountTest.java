package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.junit.jupiter.params.provider.ValueSource;
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
 * Colochos de OUTPUT y REMAINDER en una transformación SPECIAL. Son dos piezas
 * físicas distintas, así que cada una lleva la cantidad que el operador decide
 * para ella y ninguna hereda la de la otra ni la de la entrada.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationCurlCountTest {

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

    /** Familia real: declara el atributo, como deja la migración V22. */
    private final Family family = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN),
            attribute(CURLS, AttributeDataType.NUMBER));

    private static Attribute attribute(String name, AttributeDataType dataType) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(false)
                .build();
    }

    private static Family familyDeclaring(Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>(List.of(declared)))
                .build();
    }

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

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put(
                    (String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    private Product product(Map<String, Object> productAttributes) {
        return Product.builder()
                .id(UUID.randomUUID())
                .family(family)
                .model(Model.builder().id(UUID.randomUUID()).build())
                .attributes(productAttributes)
                .build();
    }

    private InventoryItem itemOf(Map<String, Object> productAttributes) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(product(productAttributes))
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
            UUID outputProduct,
            Map<String, Object> outputAttributes,
            Integer outputCurlCount,
            Integer remainderCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, outputProduct, null,
                outputAttributes, outputCurlCount, remainderCurlCount, null);
    }

    private Transformation transform(
            InventoryItem inputItem, CreateTransformationRequestDTO requestDTO) {
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem, requestDTO);
        return transformation;
    }

    private Optional<TransformationItem> itemWithRole(
            Transformation transformation, TransformationItemRole role) {
        return transformation.getItems()
                .stream()
                .filter(item -> item.getRole() == role)
                .findFirst();
    }

    private Map<String, Object> attributesOf(
            Transformation transformation, TransformationItemRole role) {
        return itemWithRole(transformation, role)
                .orElseThrow()
                .getItem()
                .getInventory()
                .getProduct()
                .getAttributes();
    }

    // ------------------------------------------------------ OUTPUT: cantidad

    @ParameterizedTest(name = "outputCurlCount = {0}")
    @ValueSource(ints = { 0, 1, 2, 7 })
    void theRequestedCurlCountLandsOnTheMainPiece(int curls) {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 5));

        Transformation transformation = transform(
                inputItem, request(null, null, curls, null));

        assertThat(attributesOf(transformation, TransformationItemRole.OUTPUT))
                .containsEntry(CURLS, curls);
    }

    @Test
    void changingOnlyTheCurlCountKeepsEveryOtherAttribute() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = transform(
                inputItem, request(null, null, 2, null));

        Map<String, Object> output =
                attributesOf(transformation, TransformationItemRole.OUTPUT);
        assertThat(output).containsEntry("largo", 1200);
        assertThat(output).containsEntry("tieneOrificio", false);
        assertThat(output).containsEntry(CURLS, 2);
    }

    /** Solo cambian los colochos: el largo no se mueve, así que no hay sobrante. */
    @Test
    void changingOnlyTheCurlCountProducesNoRemainder() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = transform(
                inputItem, request(null, null, 2, null));

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
        assertThat(transformation.getItems())
                .extracting(TransformationItem::getRole)
                .containsExactlyInAnyOrder(
                        TransformationItemRole.OUTPUT,
                        TransformationItemRole.INPUT);
    }

    @Test
    void changingOnlyTheCurlCountConsumesInputAndPublishesOutput() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = transform(
                inputItem, request(null, null, 2, null));

        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
        InventoryItem output = itemWithRole(
                transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem();
        assertThat(output.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(output.getInventory().getStock()).isEqualTo(1);
    }

    @Test
    void lengthAndCurlCountCanChangeTogetherWithoutRemainder() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        // 1200 -> 900: la diferencia no llega a la mitad, no hay sobrante.
        Transformation transformation = transform(inputItem,
                request(null, Map.of("largo", 900), 2, null));

        Map<String, Object> output =
                attributesOf(transformation, TransformationItemRole.OUTPUT);
        assertThat(output).containsEntry("largo", 900);
        assertThat(output).containsEntry("tieneOrificio", false);
        assertThat(output).containsEntry(CURLS, 2);
        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    /**
     * Omitir el conteo ya no significa "conservar": en una familia que maneja
     * colochos, la cantidad de la pieza principal la decide siempre el operador.
     */
    @Test
    void omittingTheOutputCurlCountIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 2));

        assertThatThrownBy(() -> transform(inputItem,
                request(null, Map.of("largo", 900), null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debes indicar la cantidad de colochos de la pieza resultante");

        verify(productRepository, never()).save(any(Product.class));
        assertThat(inputItem.getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    @Test
    void theOutputCurlCountIsAlsoRequiredWhenAProductIsChosen() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));
        Product chosen = product(attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 3));
        lenient().when(productRepository.findById(chosen.getId()))
                .thenReturn(Optional.of(chosen));

        assertThatThrownBy(() -> transform(
                inputItem, request(chosen.getId(), null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debes indicar la cantidad de colochos de la pieza resultante");
    }

    @Test
    void anIdenticalPieceIsStillRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 2));

        assertThatThrownBy(() -> transform(
                inputItem, request(null, null, 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La pieza resultante debe diferenciarse del producto de entrada");

        verify(productRepository, never()).save(any(Product.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
    }

    /** Un conteo guardado como 2.0 es el mismo que 2: no es transformación. */
    @Test
    void aStoredDecimalCountIsEquivalentToTheRequestedInteger() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 2.0));

        assertThatThrownBy(() -> transform(
                inputItem, request(null, null, 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La pieza resultante debe diferenciarse del producto de entrada");
    }

    // ------------------------------------ familia SPECIAL sin esta capacidad

    /** Pieza especial cuya familia no declara colochos: el concepto no existe. */
    private InventoryItem plainItem(Map<String, Object> productAttributes) {
        Family plainFamily = familyDeclaring(
                attribute("largo", AttributeDataType.NUMBER),
                attribute("tieneOrificio", AttributeDataType.BOOLEAN));
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(Product.builder()
                        .id(UUID.randomUUID())
                        .family(plainFamily)
                        .model(Model.builder().id(UUID.randomUUID()).build())
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

    @Test
    void aFamilyWithoutCurlSupportTransformsWithoutAnyCurlField() {
        InventoryItem inputItem = plainItem(attributes(
                "largo", 1200, "tieneOrificio", false));

        Transformation transformation = transform(inputItem,
                request(null, Map.of("largo", 900), null, null));

        Map<String, Object> output =
                attributesOf(transformation, TransformationItemRole.OUTPUT);
        assertThat(output).containsEntry("largo", 900);
        assertThat(output).doesNotContainKey(CURLS);
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
    }

    /** El sobrante de una familia sin colochos no exige declararlos. */
    @Test
    void aFamilyWithoutCurlSupportBuildsItsRemainderWithoutCurlCount() {
        InventoryItem inputItem = plainItem(attributes(
                "largo", 1200, "tieneOrificio", false));

        Transformation transformation = transform(inputItem,
                request(null, Map.of("largo", 600), null, null));

        Map<String, Object> remainder =
                attributesOf(transformation, TransformationItemRole.REMAINDER);
        assertThat(remainder).containsEntry("largo", 600.0);
        assertThat(remainder).doesNotContainKey(CURLS);
    }

    @Test
    void aFamilyWithoutCurlSupportRejectsAnOutputCurlCount() {
        InventoryItem inputItem = plainItem(attributes(
                "largo", 1200, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(
                inputItem, request(null, Map.of("largo", 900), 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La familia del producto no admite cantidad de colochos");

        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void aFamilyWithoutCurlSupportRejectsARemainderCurlCount() {
        InventoryItem inputItem = plainItem(attributes(
                "largo", 1200, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(
                inputItem, request(null, Map.of("largo", 600), null, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La familia del producto no admite cantidad de colochos");
    }

    // ------------------------------------------- OUTPUT con producto elegido

    /**
     * Aunque el operador elija un producto concreto, el conteo pedido manda: se
     * resuelve otra pieza equivalente en lugar de tocar la elegida, que puede
     * estar en uso por inventario e historial.
     */
    @Test
    void aChosenOutputProductIsNeverMutatedByTheRequestedCurlCount() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));
        Map<String, Object> chosenAttributes = attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 0);
        Product chosen = product(chosenAttributes);
        lenient().when(productRepository.findById(chosen.getId()))
                .thenReturn(Optional.of(chosen));

        Transformation transformation = transform(
                inputItem, request(chosen.getId(), null, 2, null));

        // El producto elegido conserva sus atributos originales.
        assertThat(chosen.getAttributes()).containsEntry(CURLS, 0);
        assertThat(chosenAttributes).containsEntry(CURLS, 0);
        // Y la salida usa otra pieza con la cantidad pedida.
        Map<String, Object> output =
                attributesOf(transformation, TransformationItemRole.OUTPUT);
        assertThat(output).containsEntry(CURLS, 2);
        assertThat(output).containsEntry("largo", 900);
    }

    @Test
    void aChosenOutputProductIsReusedWhenTheCurlCountAlreadyMatches() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));
        Product chosen = product(attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 2));
        lenient().when(productRepository.findById(chosen.getId()))
                .thenReturn(Optional.of(chosen));

        Transformation transformation = transform(
                inputItem, request(chosen.getId(), null, 2, null));

        // Coincide con lo pedido: no hace falta crear ni buscar otro producto.
        verify(productRepository, never()).save(any(Product.class));
        assertThat(itemWithRole(transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem()
                .getInventory()
                .getProduct())
                .isSameAs(chosen);
    }

    // ---------------------------------------------------------- REMAINDER

    /** 1200 -> 600 deja un sobrante de 600 con la regla de largo existente. */
    private Transformation cutInHalf(
            InventoryItem inputItem, Integer outputCurls, Integer remainderCurls) {
        return transform(inputItem,
                request(null, Map.of("largo", 600), outputCurls, remainderCurls));
    }

    @ParameterizedTest(name = "remainderCurlCount = {0}")
    @ValueSource(ints = { 0, 1, 2 })
    void theRemainderTakesItsOwnCurlCount(int curls) {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = cutInHalf(inputItem, 2, curls);

        assertThat(attributesOf(transformation, TransformationItemRole.REMAINDER))
                .containsEntry(CURLS, curls)
                .containsEntry("largo", 600.0);
    }

    @Test
    void outputAndRemainderKeepIndependentCurlCounts() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = cutInHalf(inputItem, 2, 1);

        assertThat(attributesOf(transformation, TransformationItemRole.OUTPUT))
                .containsEntry("largo", 600)
                .containsEntry(CURLS, 2);
        assertThat(attributesOf(transformation, TransformationItemRole.REMAINDER))
                .containsEntry("largo", 600.0)
                .containsEntry(CURLS, 1);
    }

    @Test
    void theMainPieceCanHaveNoCurlsWhileTheRemainderDoes() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 2));

        Transformation transformation = cutInHalf(inputItem, 0, 1);

        assertThat(attributesOf(transformation, TransformationItemRole.OUTPUT))
                .containsEntry(CURLS, 0);
        assertThat(attributesOf(transformation, TransformationItemRole.REMAINDER))
                .containsEntry(CURLS, 1);
    }

    @Test
    void theMainPieceCanHaveCurlsWhileTheRemainderDoesNot() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = cutInHalf(inputItem, 2, 0);

        assertThat(attributesOf(transformation, TransformationItemRole.OUTPUT))
                .containsEntry(CURLS, 2);
        assertThat(attributesOf(transformation, TransformationItemRole.REMAINDER))
                .containsEntry(CURLS, 0);
    }

    /** El sobrante no hereda los colochos de la entrada. */
    @Test
    void theRemainderDoesNotInheritTheInputCurlCount() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 5));

        Transformation transformation = cutInHalf(inputItem, 5, 0);

        assertThat(attributesOf(transformation, TransformationItemRole.REMAINDER))
                .containsEntry(CURLS, 0);
    }

    // -------------------------------------------------- contrato del sobrante

    @Test
    void aRemainderWithoutItsCurlCountIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        assertThatThrownBy(() -> cutInHalf(inputItem, 2, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debes indicar la cantidad de colochos del sobrante");

        // Se rechaza antes de materializar nada.
        verify(productRepository, never()).save(any(Product.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
        assertThat(inputItem.getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    @Test
    void configuringCurlsForARemainderThatWillNotExistIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));

        // Solo cambian los colochos: no habrá sobrante que configurar.
        assertThatThrownBy(() -> transform(
                inputItem, request(null, null, 2, 1)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La transformacion no genera un sobrante al cual asignar colochos");

        verify(productRepository, never()).save(any(Product.class));
        assertThat(inputItem.getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    /** La regla que decide si hay sobrante no cambia: el orificio lo impide. */
    @Test
    void aPieceWithAHoleStillProducesNoRemainder() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", true, CURLS, 0));

        Transformation transformation = transform(inputItem,
                request(null, Map.of("largo", 600), 2, null));

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    // -------------------------------------------------- producto equivalente

    @Test
    void anEquivalentProductIsReusedAndOnlyANewUnitIsCreated() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));
        Product existing = product(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 2));
        lenient().when(productRepository.findTargetProduct(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        Transformation transformation = transform(
                inputItem, request(null, null, 2, null));

        verify(productRepository, never()).save(any(Product.class));
        InventoryItem output = itemWithRole(
                transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem();
        assertThat(output.getInventory().getProduct()).isSameAs(existing);
        assertThat(output.getId()).isNotEqualTo(inputItem.getId());
    }

    static Stream<Arguments> curlCountsThatAreTheSamePiece() {
        return Stream.of(
                Arguments.of("entero", 2),
                Arguments.of("decimal exacto", 2.0),
                Arguments.of("decimal con ceros", new java.math.BigDecimal("2.00")));
    }

    /** 2, 2.0 y 2.00 son la misma cantidad y no deben duplicar productos. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("curlCountsThatAreTheSamePiece")
    void storedRepresentationsOfTwoAreEquivalent(String description, Object stored) {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, stored));

        assertThatThrownBy(() -> transform(
                inputItem, request(null, null, 2, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La pieza resultante debe diferenciarse del producto de entrada");
    }

}
