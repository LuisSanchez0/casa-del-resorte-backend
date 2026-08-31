package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.product.repository.ProductRepository;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SpecialTransformationHandlerTest {

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
    private final Workshop workshop = Workshop.builder()
            .id(workshopId)
            .build();
    /**
     * Familia SPECIAL que NO declara `cantidadColochos`: se transforma sin que la
     * configuración de colochos intervenga, ni siquiera al generar sobrante.
     */
    private final Family family = Family.builder()
            .id(UUID.randomUUID())
            .category(FamilyCategory.SPECIAL)
            .build();

    @BeforeEach
    void stubItemPersistence() {
        // findOrCreateItem persiste la unidad para que Hibernate le asigne id
        // antes de que TransformationItem la referencie.
        lenient().when(itemRepository.save(any(InventoryItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
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
        lenient().when(inventoryRepository
                .findByProductIdAndWorkshopId(any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(workshopRepository.findById(workshopId))
                .thenReturn(Optional.of(workshop));
        lenient().when(productRepository
                .findTargetProduct(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generatesRemainderWhenAttributesAreUppercase() {
        Transformation transformation = transform(
                attributes("LARGO", 1200, "TIENEORIFICIO", false),
                attributes("LARGO", 500, "TIENEORIFICIO", false));

        Map<String, Object> remainder = remainderAttributes(transformation);
        assertThat(lengthOf(remainder, "LARGO")).isEqualTo(700.0);
        assertThat(remainder).containsKey("LARGO");
    }

    @Test
    void generatesRemainderWhenAttributesAreLowercase() {
        Transformation transformation = transform(
                attributes("largo", 1200, "tieneorificio", false),
                attributes("largo", 500, "tieneorificio", false));

        Map<String, Object> remainder = remainderAttributes(transformation);
        assertThat(lengthOf(remainder, "largo")).isEqualTo(700.0);
        assertThat(remainder.containsKey("largo")).isTrue();
        assertThat(remainder.containsKey("LARGO")).isFalse();
    }

    @Test
    void generatesRemainderWhenAttributesMixCase() {
        Transformation transformation = transform(
                attributes("Largo", 1200, "TieneOrificio", false),
                attributes("LaRgO", 500, "tIeNeOrIfIcIo", false));

        Map<String, Object> remainder = remainderAttributes(transformation);
        assertThat(lengthOf(remainder, "Largo")).isEqualTo(700.0);
        assertThat(remainder.containsKey("Largo")).isTrue();
        assertThat(remainder.keySet())
                .doesNotContain("LARGO", "largo", "LaRgO");
    }

    static Stream<Arguments> numberSubtypes() {
        return Stream.of(
                arguments("Integer", Integer.valueOf(1200), Integer.valueOf(500)),
                arguments("Long", Long.valueOf(1200L), Long.valueOf(500L)),
                arguments("Double", Double.valueOf(1200.0), Double.valueOf(500.0)),
                arguments("Float", Float.valueOf(1200f), Float.valueOf(500f)),
                arguments("BigDecimal",
                        new BigDecimal("1200.0"), new BigDecimal("500.0")),
                arguments("BigInteger",
                        BigInteger.valueOf(1200), BigInteger.valueOf(500)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("numberSubtypes")
    void readsTheLengthFromAnyNumberSubtype(
            String subtype, Number inputLength, Number outputLength) {
        Transformation transformation = transform(
                attributes("largo", inputLength, "tieneOrificio", false),
                attributes("largo", outputLength, "tieneOrificio", false));

        assertThat(lengthOf(remainderAttributes(transformation), "largo"))
                .isEqualTo(700.0);
    }

    @Test
    void doesNotGenerateRemainderWhenTheHoleFlagIsTrueInLowercase() {
        Transformation transformation = transform(
                attributes("largo", 1200, "tieneorificio", true),
                attributes("largo", 500, "tieneorificio", false), null);

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    @Test
    void doesNotTreatANumericStringAsALength() {
        Transformation transformation = transform(
                attributes("largo", "1200", "tieneOrificio", false),
                attributes("largo", 500, "tieneOrificio", false), null);

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    @Test
    void doesNotTreatABooleanStringAsTheHoleFlag() {
        Transformation transformation = transform(
                attributes("largo", 1200, "tieneOrificio", "true"),
                attributes("largo", 500, "tieneOrificio", false));

        assertThat(lengthOf(remainderAttributes(transformation), "largo"))
                .isEqualTo(700.0);
    }

    @Test
    void doesNotGenerateRemainderWhenTheInputLengthIsMissing() {
        Transformation transformation = transform(
                attributes("tieneOrificio", false),
                attributes("largo", 500, "tieneOrificio", false), null);

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    @Test
    void doesNotGenerateRemainderWhenTheOutputLengthIsMissing() {
        Transformation transformation = transform(
                attributes("largo", 1200, "tieneOrificio", false),
                attributes("tieneOrificio", false), null);

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    @Test
    void doesNotGenerateRemainderWhenTheDifferenceIsBelowHalfTheInput() {
        InventoryItem inputItem = itemOf(product(
                attributes("largo", 1200, "tieneOrificio", false)));
        Product outputProduct = product(
                attributes("largo", 800, "tieneOrificio", false));
        when(productRepository.findById(outputProduct.getId()))
                .thenReturn(Optional.of(outputProduct));
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, inputItem, request(outputProduct.getId(), null));

        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
        assertThat(itemWithRole(transformation, TransformationItemRole.OUTPUT))
                .isPresent();
        assertThat(itemWithRole(transformation, TransformationItemRole.INPUT))
                .containsSame(transformationItemOf(inputItem, transformation));
        assertThat(inputItem.getStatus())
                .isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(inputItem.getInventory().getStock()).isEqualTo(4);
        assertThat(outputItemOf(transformation).getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(outputItemOf(transformation)
                .getInventory().getProduct()).isSameAs(outputProduct);
    }

    @Test
    void doesNotFailWhenTheInputAttributesAreNull() {
        assertThatCode(() -> {
            Transformation transformation = transform(null,
                    attributes("largo", 500, "tieneOrificio", false), null);
            assertThat(itemWithRole(
                    transformation, TransformationItemRole.REMAINDER)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void doesNotFailWhenTheOutputAttributesAreNull() {
        assertThatCode(() -> {
            Transformation transformation = transform(
                    attributes("largo", 1200, "tieneOrificio", false), null, null);
            assertThat(itemWithRole(
                    transformation, TransformationItemRole.REMAINDER)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void ignoresNullKeysWhileLookingUpAnAttribute() {
        Map<String, Object> inputAttributes = attributes(
                "largo", 1200, "tieneOrificio", false);
        inputAttributes.put(null, 999);

        Transformation transformation = transform(inputAttributes,
                attributes("largo", 500, "tieneOrificio", false));

        Map<String, Object> remainder = remainderAttributes(transformation);
        assertThat(lengthOf(remainder, "largo")).isEqualTo(700.0);
        assertThat(remainder.containsKey("LARGO")).isFalse();
    }

    private Transformation transform(
            Map<String, Object> inputAttributes,
            Map<String, Object> outputAttributes) {
        return transform(inputAttributes, outputAttributes, null);
    }

    private Transformation transform(
            Map<String, Object> inputAttributes,
            Map<String, Object> outputAttributes,
            Integer remainderCurlCount) {
        InventoryItem inputItem = itemOf(product(inputAttributes));
        Product outputProduct = product(outputAttributes);
        when(productRepository.findById(outputProduct.getId()))
                .thenReturn(Optional.of(outputProduct));
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem,
                request(outputProduct.getId(), remainderCurlCount));
        return transformation;
    }

    private CreateTransformationRequestDTO request(
            UUID outputProduct, Integer remainderCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, outputProduct, null, null,
                null, remainderCurlCount, null);
    }

    private Product product(Map<String, Object> productAttributes) {
        return Product.builder()
                .id(UUID.randomUUID())
                .family(family)
                .attributes(productAttributes)
                .build();
    }

    private InventoryItem itemOf(Product product) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(product)
                .stock(5)
                .build();
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(item);
        return item;
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put(
                    (String) keysAndValues[index],
                    keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    private Optional<TransformationItem> itemWithRole(
            Transformation transformation, TransformationItemRole role) {
        return transformation.getItems()
                .stream()
                .filter(item -> item.getRole() == role)
                .findFirst();
    }

    private TransformationItem transformationItemOf(
            InventoryItem item, Transformation transformation) {
        return transformation.getItems()
                .stream()
                .filter(transformationItem -> transformationItem
                        .getItem() == item)
                .findFirst()
                .orElseThrow();
    }

    private InventoryItem outputItemOf(Transformation transformation) {
        return itemWithRole(transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem();
    }

    private Map<String, Object> remainderAttributes(
            Transformation transformation) {
        return itemWithRole(transformation, TransformationItemRole.REMAINDER)
                .orElseThrow()
                .getItem()
                .getInventory()
                .getProduct()
                .getAttributes();
    }

    private double lengthOf(Map<String, Object> productAttributes, String key) {
        assertThat(productAttributes.get(key)).isInstanceOf(Number.class);
        return ((Number) productAttributes.get(key)).doubleValue();
    }

}