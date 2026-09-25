package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * La autoridad de la regla MATERIAL LISO -> HOJA DE RESORTE.
 *
 * El frontend solo decide QUE se ofrece; aqui se decide que se ACEPTA. Por eso
 * cada caso llama al manejador directamente con el `outputProduct` ya dentro de
 * la peticion, que es exactamente lo que llegaria de un cliente fabricado a
 * mano: si el destino no es compatible tiene que rebotar aunque el selector
 * nunca lo hubiera mostrado.
 *
 * Las dos familias se construyen con los atributos que tienen de verdad tras la
 * migracion V36: el MISMO vocabulario de transformacion, `cantidadColochos`
 * incluido. Lo unico que las separa ya es si exigen modelo.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationCrossFamilyTest {

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

    @Mock
    private InventoryLotService lotService;

    @InjectMocks
    private SpecialTransformationHandler handler;

    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder().id(workshopId).build();
    private final Model model = Model.builder().id(UUID.randomUUID()).build();

    /**
     * MATERIAL LISO tal y como queda tras V36: el MISMO conjunto de
     * transformacion que la hoja —`largo`, `tieneOrificio`, los dos largos de
     * cada lado y `cantidadColochos`—. La paridad es la premisa de la regla.
     */
    private final Family materialLiso = family("ML", "MATERIAL LISO",
            attribute("largo", AttributeDataType.NUMBER),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN),
            attribute("largoIzquierdo", AttributeDataType.NUMBER),
            attribute("largoDerecho", AttributeDataType.NUMBER),
            attribute("ancho", AttributeDataType.NUMBER),
            attribute(CURLS, AttributeDataType.NUMBER));

    /**
     * Una familia SPECIAL que no declara colochos. Existe para fijar que la
     * capacidad depende del atributo declarado y de nada mas.
     */
    private final Family withoutCurls = family("SC", "ESPECIAL SIN COLOCHOS",
            attribute("largo", AttributeDataType.NUMBER),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN));

    /** HOJA DE RESORTE: el mismo conjunto mas lo que solo es suyo. */
    private final Family hojaResorte = family("HR", "HOJA DE RESORTE",
            attribute("largo", AttributeDataType.NUMBER),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN),
            attribute("largoIzquierdo", AttributeDataType.NUMBER),
            attribute("largoDerecho", AttributeDataType.NUMBER),
            attribute("ancho", AttributeDataType.NUMBER),
            attribute("posicionEje", AttributeDataType.STRING),
            attribute(CURLS, AttributeDataType.NUMBER));

    /** Una familia sin ninguna relacion con las dos anteriores. */
    private final Family unrelated = family("RES", "RESORTE ESPIRAL",
            attribute("largo", AttributeDataType.NUMBER),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN));

    // ── B. MATERIAL LISO -> HOJA DE RESORTE se acepta ──────────────────

    @Test
    void aMaterialLisoPieceCanBecomeALeafSpring() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false, "ancho", 80));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", false,
                "posicionEje", "DELANTERO", CURLS, 2));

        Transformation transformation = transform(inputItem, request(leaf));

        Product result = outputProductOf(transformation);
        assertThat(result.getFamily()).isSameAs(hojaResorte);
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
    }

    /**
     * I. La pieza resultante usa las caracteristicas GUARDADAS de la hoja, no
     * las del material liso. Arrastrar `ancho` de la entrada encima de la hoja
     * escribiria en ella un dato que el operador no pidio.
     */
    @Test
    void theResultUsesTheSelectedLeafSpringOwnAttributes() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false, "ancho", 80));
        Map<String, Object> leafAttributes = attributes(
                "largo", 900, "tieneOrificio", false,
                "posicionEje", "DELANTERO", CURLS, 2);
        Product leaf = productOf(hojaResorte, leafAttributes);

        Transformation transformation = transform(inputItem, request(leaf));

        assertThat(outputAttributesOf(transformation)).isEqualTo(leafAttributes);
    }

    /**
     * G. La hoja elegida ya describe el resultado, asi que se REUTILIZA: ni se
     * crea un producto duplicado ni se toca el elegido.
     */
    @Test
    void theSelectedLeafSpringIsReusedInsteadOfDuplicated() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Map<String, Object> leafAttributes = attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 0);
        Product leaf = productOf(hojaResorte, leafAttributes);
        Map<String, Object> before = new HashMap<>(leafAttributes);

        Transformation transformation = transform(inputItem, request(leaf));

        verify(productRepository, never()).save(any(Product.class));
        assertThat(outputProductOf(transformation)).isSameAs(leaf);
        assertThat(leaf.getAttributes()).isEqualTo(before);
    }

    /** La unidad resultante nace disponible y el lote de la entrada se consume. */
    @Test
    void theResultingUnitIsPublishedOnceAndTheInputIsConsumed() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 0));

        Transformation transformation = transform(inputItem, request(leaf));

        verify(itemRepository).save(any(InventoryItem.class));
        InventoryItem output = itemWithRole(transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem();
        assertThat(output.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(output.getInventory().getStock()).isEqualTo(1);
        assertThat(output.getInventory().getWorkshop()).isSameAs(workshop);
    }

    /**
     * El sobrante sigue siendo MATERIAL LISO: lo que sobra del corte no se
     * convierte en hoja, se queda siendo el material del que salio.
     */
    @Test
    void theRemainderStaysInTheInputFamily() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false, "ancho", 80));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 500, "tieneOrificio", false, CURLS, 0));

        // El recorte es una pieza de MATERIAL LISO, que declara colochos: su
        // cantidad se exige aparte de la principal, que la fija el destino.
        Transformation transformation = transform(
                inputItem, requestWithRemainderCurls(leaf, 0));

        Product remainder = itemWithRole(transformation, TransformationItemRole.REMAINDER)
                .orElseThrow()
                .getItem()
                .getInventory()
                .getProduct();
        assertThat(remainder.getFamily()).isSameAs(materialLiso);
        assertThat(remainder.getAttributes()).containsEntry("largo", 700.0);
        assertThat(remainder.getAttributes()).containsEntry("ancho", 80);
    }

    // ── C / I. Una familia ajena sigue rechazandose ────────────────────

    @Test
    void anUnrelatedFamilyTargetIsRejected() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product forged = productOf(unrelated, attributes(
                "largo", 900, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(inputItem, request(forged)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no pertenece a una familia");

        verify(productRepository, never()).save(any(Product.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    // ── D. El sentido contrario no cambia ──────────────────────────────

    @Test
    void aLeafSpringInputStillCannotTargetMaterialLiso() {
        InventoryItem inputItem = itemOf(hojaResorte, attributes(
                "largo", 1200, "tieneOrificio", false, CURLS, 2));
        Product material = productOf(materialLiso, attributes(
                "largo", 900, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(inputItem, requestWithCurls(material, 2)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no pertenece a una familia");

        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    // ── E. El largo sigue mandando por encima de la compatibilidad ─────

    @Test
    void aLeafSpringLongerThanTheInputIsRejected() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 100, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 120, "tieneOrificio", false, CURLS, 0));

        assertThatThrownBy(() -> transform(inputItem, request(leaf)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("El largo resultante no puede ser mayor");

        verify(itemRepository, never()).save(any(InventoryItem.class));
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    @Test
    void aLeafSpringShorterThanOrEqualToTheInputIsAccepted() {
        Product shorter = productOf(hojaResorte, attributes(
                "largo", 80, "tieneOrificio", false, CURLS, 0));
        Product equal = productOf(hojaResorte, attributes(
                "largo", 100, "tieneOrificio", false, CURLS, 0));

        assertThatCode(() -> transform(
                materialLisoItem(attributes("largo", 100, "tieneOrificio", false)),
                request(shorter)))
                .doesNotThrowAnyException();
        assertThatCode(() -> transform(
                materialLisoItem(attributes("largo", 100, "tieneOrificio", false)),
                request(equal)))
                .doesNotThrowAnyException();
    }

    // ── F. Las medidas de orificio siguen siendo exactas ───────────────

    /**
     * La hoja declara el conjunto completo de orificio, asi que es ELLA quien
     * decide si las medidas del resultado cuadran. Sin comprobar contra la
     * familia de destino, una hoja con los dos lados descuadrados entraria por
     * la puerta de atras que abre la compatibilidad.
     */
    @Test
    void aLeafSpringWithMismatchedHoleMeasurementsIsRejected() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", true,
                "largoIzquierdo", 400, "largoDerecho", 400, CURLS, 0));

        assertThatThrownBy(() -> transform(inputItem, request(leaf)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exactamente igual al largo total");

        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    @Test
    void aLeafSpringWithExactHoleMeasurementsIsAccepted() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", true,
                "largoIzquierdo", 400, "largoDerecho", 500, CURLS, 0));

        Transformation transformation = transform(inputItem, request(leaf));

        assertThat(outputProductOf(transformation).getFamily()).isSameAs(hojaResorte);
        // Con orificio no hay sobrante, sea cual sea el recorte.
        assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER)).isEmpty();
    }

    /**
     * Una hoja guardada con una clave que su propia familia no declara no se
     * propaga a la pieza resultante: se rechaza, igual que en la misma familia.
     */
    @Test
    void aLeafSpringWithAnUndeclaredAttributeIsRejected() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", false, "colorPintura", "NEGRO"));

        assertThatThrownBy(() -> transform(inputItem, request(leaf)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("colorPintura");
    }

    // ── Los colochos siguen dirigidos por el ATRIBUTO ──────────────────

    /**
     * Tras la paridad, MATERIAL LISO declara `cantidadColochos` igual que la
     * hoja, asi que dentro de su propia familia la cantidad se exige como en
     * cualquier otra familia que lo declare. Ya no queda ninguna excepcion
     * escrita por familia.
     */
    @Test
    void materialLisoRequiresACurlCountForItsOwnFamilyResults() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(inputItem, customRequest(Map.of("largo", 900))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cantidad de colochos de la pieza resultante");
    }

    /**
     * Cambiando de familia la cantidad ya la fija el producto elegido: pedir
     * otra describiria una pieza que dice ser ese producto sin serlo. No es que
     * el material liso "no admita" colochos —los admite—, es que en ese modo no
     * hay nada que preguntar.
     */
    @Test
    void aCurlCountIsRejectedWhenTheChosenProductAlreadyFixesIt() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 2));

        assertThatThrownBy(() -> transform(inputItem, requestWithCurls(leaf, 5)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("es el producto de salida elegido");

        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
    }

    /** Y la pieza resultante conserva los colochos que la hoja ya traia. */
    @Test
    void theResultKeepsTheCurlCountTheChosenProductHad() {
        InventoryItem inputItem = materialLisoItem(attributes(
                "largo", 1200, "tieneOrificio", false));
        Product leaf = productOf(hojaResorte, attributes(
                "largo", 900, "tieneOrificio", false, CURLS, 2));

        Transformation transformation = transform(inputItem, request(leaf));

        assertThat(outputAttributesOf(transformation)).containsEntry(CURLS, 2);
    }

    /**
     * Una familia que NO declara el atributo sigue rechazando la cantidad: la
     * capacidad depende de lo declarado, no de como se llame la familia.
     */
    @Test
    void aFamilyWithoutTheAttributeStillRejectsACurlCount() {
        InventoryItem inputItem = itemOf(withoutCurls, attributes(
                "largo", 1200, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(inputItem, customRequestWithCurls(
                Map.of("largo", 900), 2)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no admite cantidad de colochos");
    }

    // ── Auxiliares ─────────────────────────────────────────────────────

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

    private static Attribute attribute(String name, AttributeDataType dataType) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(false)
                .build();
    }

    private static Family family(String code, String name, Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
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

    private InventoryItem materialLisoItem(Map<String, Object> productAttributes) {
        return itemOf(materialLiso, productAttributes);
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

    /** Transformacion DENTRO de la familia de la entrada, por caracteristicas. */
    private CreateTransformationRequestDTO customRequest(Map<String, Object> outputAttributes) {
        return customRequestWithCurls(outputAttributes, null);
    }

    private CreateTransformationRequestDTO customRequestWithCurls(
            Map<String, Object> outputAttributes, Integer outputCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, null, null,
                outputAttributes, outputCurlCount, null, null);
    }

    private CreateTransformationRequestDTO request(Product selected) {
        return requestWithCurls(selected, null);
    }

    private CreateTransformationRequestDTO requestWithCurls(
            Product selected, Integer outputCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, selected.getId(), null,
                null, outputCurlCount, null, null);
    }

    private CreateTransformationRequestDTO requestWithRemainderCurls(
            Product selected, Integer remainderCurlCount) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, selected.getId(), null,
                null, null, remainderCurlCount, null);
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

    private Product outputProductOf(Transformation transformation) {
        return itemWithRole(transformation, TransformationItemRole.OUTPUT)
                .orElseThrow()
                .getItem()
                .getInventory()
                .getProduct();
    }

    private Map<String, Object> outputAttributesOf(Transformation transformation) {
        return outputProductOf(transformation).getAttributes();
    }

}
