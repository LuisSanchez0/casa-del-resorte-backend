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
 * La regla de medidas con orificio en una transformacion personalizada.
 *
 * La familia de esta prueba declara el conjunto completo
 * {@code largo + tieneOrificio + largoIzquierdo + largoDerecho}, asi que hereda
 * el comportamiento. La comparacion se hace SIEMPRE contra el largo efectivo de
 * la pieza resultante —el de la entrada, o el que pise el PATCH— y un
 * incumplimiento se rechaza antes de materializar nada.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationHoleMeasurementTest {

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

    /** Familia que declara el conjunto completo de medidas con orificio. */
    private final Family family = familyDeclaring(
            attribute("largo", AttributeDataType.NUMBER, true),
            attribute("tieneOrificio", AttributeDataType.BOOLEAN, true),
            attribute("largoIzquierdo", AttributeDataType.NUMBER, false),
            attribute("largoDerecho", AttributeDataType.NUMBER, false));

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

    // ── tieneOrificio == false ───────────────────────────────────────

    @Test
    void withoutHoleACustomTransformationDoesNotNeedSideLengths() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1200, "tieneOrificio", false));

        assertThatCode(() -> transform(inputItem, custom(Map.of("largo", 900))))
                .doesNotThrowAnyException();
    }

    // ── tieneOrificio == true ────────────────────────────────────────

    @Test
    void withHoleASideSumEqualToTheEffectiveLengthIsAllowed() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", true,
                "largoIzquierdo", 400, "largoDerecho", 400));

        Map<String, Object> output = outputAttributesOf(transform(
                inputItem, custom(Map.of("largoIzquierdo", 500, "largoDerecho", 500))));

        assertThat(output)
                .containsEntry("largo", 1000)
                .containsEntry("largoIzquierdo", 500)
                .containsEntry("largoDerecho", 500);
    }

    @Test
    void withHoleASideSumAboveTheEffectiveLengthIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", true,
                "largoIzquierdo", 400, "largoDerecho", 400));

        assertThatThrownBy(() -> transform(inputItem,
                custom(Map.of("largoIzquierdo", 600, "largoDerecho", 600))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");

        assertNothingWasMaterialised(inputItem);
    }

    @Test
    void withHoleASideSumBelowTheEffectiveLengthIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", true,
                "largoIzquierdo", 500, "largoDerecho", 500));

        assertThatThrownBy(() -> transform(inputItem,
                custom(Map.of("largoIzquierdo", 400, "largoDerecho", 500))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");

        assertNothingWasMaterialised(inputItem);
    }

    @Test
    void withHoleAZeroSideLengthIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", true,
                "largoIzquierdo", 500, "largoDerecho", 500));

        assertThatThrownBy(() -> transform(inputItem,
                custom(Map.of("largoIzquierdo", 1000, "largoDerecho", 0))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo derecho debe ser mayor a 0.");

        assertNothingWasMaterialised(inputItem);
    }

    @Test
    void withHoleAMissingSideLengthIsRejected() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", false));

        assertThatThrownBy(() -> transform(inputItem,
                custom(Map.of("tieneOrificio", true, "largoIzquierdo", 400))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debe indicar el largo derecho cuando el producto tiene orificio.");

        assertNothingWasMaterialised(inputItem);
    }

    /**
     * El PATCH baja el largo total: la suma se compara contra el largo NUEVO
     * (90), no contra el de la entrada (1000). Sin resolver el largo efectivo,
     * 450 + 450 <= 1000 pasaria por bueno.
     */
    @Test
    void theSumIsComparedAgainstThePatchedEffectiveLength() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", true,
                "largoIzquierdo", 450, "largoDerecho", 450));

        assertThatThrownBy(() -> transform(inputItem, custom(Map.of("largo", 90))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");

        assertNothingWasMaterialised(inputItem);
    }

    @Test
    void anInvalidHoleMeasurementLeavesNoPartialEntities() {
        InventoryItem inputItem = itemOf(attributes(
                "largo", 1000, "tieneOrificio", true,
                "largoIzquierdo", 400, "largoDerecho", 400));
        Transformation transformation = Transformation.builder().build();
        int stockBefore = inputItem.getInventory().getStock();

        assertThatThrownBy(() -> handler.generate(transformation, inputItem,
                custom(Map.of("largoIzquierdo", 700, "largoDerecho", 700))))
                .isInstanceOf(ValidationException.class);

        assertThat(transformation.getItems()).isEmpty();
        assertThat(inputItem.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(inputItem.getInventory().getStock()).isEqualTo(stockBefore);
        verify(productRepository, never()).save(any(Product.class));
        verify(itemRepository, never()).save(any(InventoryItem.class));
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

    private static Family familyDeclaring(Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .name("FAMILIA CON ORIFICIO")
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

    private CreateTransformationRequestDTO custom(Map<String, Object> outputAttributes) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, null, null,
                outputAttributes, null, null, null);
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
