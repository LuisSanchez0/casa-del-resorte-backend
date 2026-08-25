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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

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
 * Elegir un `outputProduct` describe la pieza resultante, no la sustituye: sus
 * características se combinan sobre las de la entrada.
 *
 * Precedencia, de menor a mayor autoridad:
 *
 * <pre>
 * atributos de la ENTRADA -> atributos del outputProduct -> outputCurlCount
 * </pre>
 *
 * La familia declarada es la de HOJA DE RESORTE real (migración V22 más los
 * atributos que la instalación tiene dados de alta por la API), para que los
 * casos sean los que el operador encuentra de verdad.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationOutputProductMergeTest {

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

    @InjectMocks
    private SpecialTransformationHandler handler;

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder().id(workshopId).build();
    private final Model model = Model.builder().id(UUID.randomUUID()).build();

    private final Family family = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER),
            attribute("ancho", AttributeDataType.NUMBER),
            attribute("grosor", AttributeDataType.NUMBER),
            attribute("numeroHoja", AttributeDataType.NUMBER),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN),
            attribute("posicionEje", AttributeDataType.STRING),
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

    // ── Casos ──────────────────────────────────────────────────────────

    /** A. El producto elegido aporta una característica que la entrada no tenía. */
    @Test
    void anAttributeOnlyOnTheSelectedProductIsAddedToTheResult() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "ancho", 80, "tieneOrificio", false));
        Product selected = productOf(family, attributes(
                "largo", 900, "posicionEje", "DELANTERO"));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(selected, 0, null)));

        assertThat(output).containsEntry("largo", 900);
        assertThat(output).containsEntry("ancho", 80);
        assertThat(output).containsEntry("posicionEje", "DELANTERO");
    }

    /** B. Lo que solo tiene la entrada se conserva; ausente no significa borrar. */
    @Test
    void anAttributeMissingFromTheSelectedProductIsKeptFromTheInput() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "numeroHoja", 3, "tieneOrificio", false));
        Product selected = productOf(family, attributes("largo", 900));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(selected, 0, null)));

        assertThat(output).containsEntry("numeroHoja", 3);
        assertThat(output).containsEntry("tieneOrificio", false);
    }

    /** C. En una característica compartida manda el producto elegido. */
    @Test
    void aSharedAttributeTakesTheValueOfTheSelectedProduct() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product selected = productOf(family, attributes("largo", 900));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(selected, 0, null)));

        assertThat(output).containsEntry("largo", 900);
    }

    /** D. Los colochos los decide la petición, nunca el producto elegido. */
    @Test
    void theRequestedCurlCountBeatsTheOneStoredInTheSelectedProduct() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 5));
        Product selected = productOf(family, attributes(
                "largo", 900, CURLS, 0));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(selected, 2, null)));

        assertThat(output).containsEntry(CURLS, 2);
    }

    /** E. El producto elegido es una referencia: nunca se modifica. */
    @Test
    void theSelectedProductIsNeverMutated() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "ancho", 80, "numeroHoja", 3, "tieneOrificio", false));
        Map<String, Object> selectedAttributes = attributes(
                "largo", 900, "posicionEje", "DELANTERO", CURLS, 0);
        Product selected = productOf(family, selectedAttributes);
        Map<String, Object> before = new HashMap<>(selectedAttributes);

        transform(inputItem, request(selected, 2, null));

        assertThat(selected.getAttributes()).isEqualTo(before);
        assertThat(selectedAttributes).isEqualTo(before);
    }

    /** F. Otra familia cambiaría lo que la pieza es: se rechaza. */
    @Test
    void aSelectedProductFromAnotherFamilyIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false));
        Family otherFamily = familyDeclaring(
                attribute("largo", AttributeDataType.NUMBER));
        Product selected = productOf(otherFamily, attributes("largo", 900));

        assertThatThrownBy(() -> transform(inputItem, request(selected, 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El producto de salida debe pertenecer a la misma familia que la pieza de entrada");

        verify(productRepository, never()).save(any(Product.class));
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    /** G. Una clave que la familia no declara no se copia en silencio. */
    @Test
    void anAttributeTheFamilyDoesNotDeclareIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product selected = productOf(family, attributes(
                "largo", 900, "colorPintura", "NEGRO"));

        assertThatThrownBy(() -> transform(inputItem, request(selected, 0, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("colorPintura");

        verify(productRepository, never()).save(any(Product.class));
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    // ── Ejemplo completo del contrato ──────────────────────────────────

    @Test
    void mergesInputSelectedProductAndCurlCountInThatOrder() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200,
                "ancho", 80,
                "grosor", 10,
                "numeroHoja", 3,
                "tieneOrificio", false));
        Product selected = productOf(family, attributes(
                "largo", 900, "posicionEje", "DELANTERO"));

        Map<String, Object> output = outputAttributesOf(
                transform(inputItem, request(selected, 2, null)));

        assertThat(output).isEqualTo(attributes(
                "largo", 900,
                "ancho", 80,
                "grosor", 10,
                "numeroHoja", 3,
                "tieneOrificio", false,
                "posicionEje", "DELANTERO",
                CURLS, 2));
    }

    /**
     * La familia de la pieza resultante es siempre la de la entrada —ya se
     * comprobó que coinciden—; el modelo sigue saliendo del producto elegido,
     * que es la regla que el backend ya tenía y que el selector del frontend
     * respalda ofreciendo únicamente productos del mismo modelo.
     */
    @Test
    void theResultingProductKeepsTheInputFamily() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product selected = productOf(family, attributes(
                "largo", 900, "posicionEje", "DELANTERO"));

        transform(inputItem, request(selected, 0, null));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        assertThat(saved.getValue().getFamily()).isSameAs(family);
        assertThat(saved.getValue().getModel()).isSameAs(model);
    }

    // ── Sobrante con el largo efectivo del resultado ───────────────────

    /**
     * El sobrante se decide con el largo que la pieza resultante tendrá de
     * verdad, que ahora sale del merge y no del producto elegido a secas.
     */
    @Test
    void theRemainderUsesTheMergedLength() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "ancho", 80, "tieneOrificio", false));
        Product selected = productOf(family, attributes("largo", 600));

        Transformation transformation = transform(
                inputItem, request(selected, 2, 1));

        Map<String, Object> remainder = attributesOf(
                transformation, TransformationItemRole.REMAINDER);
        assertThat(remainder).containsEntry("largo", 600.0);
        assertThat(remainder).containsEntry("ancho", 80);
        assertThat(remainder).containsEntry(CURLS, 1);
    }

    /** El producto elegido sin largo propio deja el de la entrada: no hay recorte. */
    @Test
    void aSelectedProductWithoutLengthLeavesTheInputLengthAndNoRemainder() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product selected = productOf(family, attributes("posicionEje", "TRASERO"));

        Transformation transformation = transform(
                inputItem, request(selected, 0, null));

        assertThat(outputAttributesOf(transformation))
                .containsEntry("largo", 1200)
                .containsEntry("posicionEje", "TRASERO");
        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    // ── Reutilización del catálogo ─────────────────────────────────────

    /**
     * Cuando el merge no aporta nada nuevo, el producto elegido ya describe la
     * pieza y se reutiliza en vez de duplicarlo.
     */
    @Test
    void theSelectedProductIsReusedWhenItAlreadyDescribesTheResult() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 0));
        Product selected = productOf(family, attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 2));

        Transformation transformation = transform(
                inputItem, request(selected, 2, null));

        verify(productRepository, never()).save(any(Product.class));
        assertThat(itemWithRole(transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem()
                .getInventory()
                .getProduct())
                .isSameAs(selected);
    }

    /** Una sola unidad física nueva, y el stock del producto elegido no se toca. */
    @Test
    void onlyOneNewUnitIsPublishedForTheResult() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "ancho", 80, "tieneOrificio", false));
        Product selected = productOf(family, attributes("largo", 900));

        Transformation transformation = transform(
                inputItem, request(selected, 0, null));

        verify(itemRepository).save(any(InventoryItem.class));
        InventoryItem output = itemWithRole(
                transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem();
        assertThat(output.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(output.getInventory().getStock()).isEqualTo(1);
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
    }

    // ── Auxiliares ─────────────────────────────────────────────────────

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put(
                    (String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    private Product productOf(Family productFamily, Map<String, Object> productAttributes) {
        Product product = Product.builder()
                .id(UUID.randomUUID())
                .family(productFamily)
                .model(model)
                .attributes(productAttributes)
                .build();
        lenient().when(productRepository.findById(product.getId()))
                .thenReturn(Optional.of(product));
        return product;
    }

    private InventoryItem itemOf(Map<String, Object> productAttributes) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(productOf(family, productAttributes))
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
            Product selected, Integer outputCurlCount, Integer remainderCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, selected.getId(), null,
                null, outputCurlCount, remainderCurlCount, null);
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

    private Map<String, Object> outputAttributesOf(Transformation transformation) {
        return attributesOf(transformation, TransformationItemRole.OUTPUT);
    }

}
