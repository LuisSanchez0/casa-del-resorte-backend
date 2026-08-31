package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Costo y nombre de la pieza resultante.
 *
 * El costo vive en `Inventory.cost` —Product no tiene ninguno— y hasta ahora el
 * inventario que creaba la transformación nacía con `BigDecimal.ZERO`: una hoja
 * que costó Q125.50 se convertía en una pieza contablemente gratis.
 *
 * El nombre lo arma el manejador a partir de la familia y el largo; el operador
 * puede sustituirlo con `outputName` sin que eso toque a los productos que ya
 * existen.
 */
@ExtendWith(MockitoExtension.class)
class SpecialTransformationOutputCostAndNameTest {

    private static final BigDecimal SOURCE_COST = new BigDecimal("125.50");
    private static final BigDecimal SOURCE_PRICE = new BigDecimal("400.00");

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

    private final Family family = Family.builder()
            .id(UUID.randomUUID())
            .name("HOJA DE RESORTE")
            .category(FamilyCategory.SPECIAL)
            .attributes(new ArrayList<>(List.of(
                    attribute("largo", AttributeDataType.NUMBER),
                    attribute("tieneOrificio", AttributeDataType.BOOLEAN))))
            .build();

    private static Attribute attribute(String name, AttributeDataType dataType) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(false)
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
        lenient().when(productRepository
                .findTargetProductNamed(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Costo ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Costo heredado del inventario de origen")
    class InheritedCost {

        @Test
        void theOutputInventoryTakesTheCostOfTheSource() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            transform(inputItem, request(Map.of("largo", 900), null));

            assertThat(createdInventories())
                    .as("el inventario de la pieza resultante")
                    .anySatisfy(inventory ->
                            assertThat(inventory.getCost()).isEqualByComparingTo(SOURCE_COST));
        }

        /** El precio de venta no es el costo: no debe confundirse uno con otro. */
        @Test
        void theOutputDoesNotInheritTheSalePrice() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            transform(inputItem, request(Map.of("largo", 900), null));

            assertThat(createdInventories())
                    .allSatisfy(inventory ->
                            assertThat(inventory.getPrice()).isEqualByComparingTo(BigDecimal.ZERO));
        }

        /**
         * El sobrante conserva su regla contable actual: nace en cero. Heredar el
         * costo completo en las dos piezas lo contaría dos veces.
         */
        @Test
        void theRemainderKeepsItsOwnAccountingRule() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            Transformation transformation = transform(
                    inputItem, request(Map.of("largo", 600), null));

            assertThat(itemWithRole(transformation, TransformationItemRole.REMAINDER)).isPresent();

            List<Inventory> created = createdInventories();
            assertThat(created).hasSize(2);
            assertThat(created).anySatisfy(inventory ->
                    assertThat(inventory.getCost()).isEqualByComparingTo(SOURCE_COST));
            assertThat(created).anySatisfy(inventory ->
                    assertThat(inventory.getCost()).isEqualByComparingTo(BigDecimal.ZERO));
        }

        /**
         * Si el taller ya tenía inventario de esa pieza se respeta su costo: la
         * transformación añade una unidad, no reescribe la contabilidad previa.
         */
        @Test
        void anExistingInventoryKeepsItsOwnCost() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));
            Inventory existing = Inventory.builder()
                    .id(UUID.randomUUID())
                    .workshop(workshop)
                    .product(Product.builder().id(UUID.randomUUID()).build())
                    .cost(new BigDecimal("10.00"))
                    .price(new BigDecimal("30.00"))
                    .stock(2)
                    .build();
            lenient().when(inventoryRepository.findByProductIdAndWorkshopId(any(), any()))
                    .thenReturn(Optional.of(existing));

            transform(inputItem, request(Map.of("largo", 900), null));

            assertThat(existing.getCost()).isEqualByComparingTo(new BigDecimal("10.00"));
            verify(inventoryRepository, org.mockito.Mockito.never()).save(any(Inventory.class));
        }
    }

    // ── Nombre ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Nombre de la pieza resultante")
    class OutputName {

        @Test
        void withoutACustomNameItKeepsTheAutomaticOne() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            transform(inputItem, request(Map.of("largo", 900), null));

            assertThat(createdProduct().getName()).isEqualTo("HOJA DE RESORTE 900");
        }

        @Test
        void aCustomNameReplacesTheAutomaticOne() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            transform(inputItem, request(Map.of("largo", 900), "Hoja reforzada especial"));

            assertThat(createdProduct().getName()).isEqualTo("Hoja reforzada especial");
        }

        /** Un nombre en blanco no es un nombre: cae en el automático. */
        @Test
        void aBlankCustomNameFallsBackToTheAutomaticOne() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            transform(inputItem, request(Map.of("largo", 900), "   "));

            assertThat(createdProduct().getName()).isEqualTo("HOJA DE RESORTE 900");
        }

        @Test
        void aCustomNameIsTrimmed() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));

            transform(inputItem, request(Map.of("largo", 900), "  Hoja reforzada  "));

            assertThat(createdProduct().getName()).isEqualTo("Hoja reforzada");
        }

        /**
         * Sin nombre pedido se reutiliza la pieza equivalente que ya existe, con
         * el nombre que ya tenía: es el comportamiento de siempre.
         */
        @Test
        void withoutACustomNameAnExistingEquivalentProductIsReused() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));
            Product existing = equivalentProduct("NOMBRE HISTORICO");
            lenient().when(productRepository.findTargetProduct(any(), any(), any()))
                    .thenReturn(Optional.of(existing));

            transform(inputItem, request(Map.of("largo", 900), null));

            assertThat(existing.getName()).isEqualTo("NOMBRE HISTORICO");
            verify(productRepository, org.mockito.Mockito.never()).save(any(Product.class));
        }

        /**
         * El fallo real: una transformación anterior ya había creado la pieza con
         * el nombre automático, así que la búsqueda por características la
         * encontraba y el nombre escrito por el operador se perdía en silencio.
         * Ahora nace una pieza nueva con ese nombre.
         */
        @Test
        void aCustomNameIsNotSwallowedByAnEquivalentProductNamedOtherwise() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));
            Product existing = equivalentProduct("HOJA DE RESORTE 900");
            lenient().when(productRepository.findTargetProduct(any(), any(), any()))
                    .thenReturn(Optional.of(existing));

            transform(inputItem, request(Map.of("largo", 900), "Hoja reforzada especial"));

            assertThat(createdProduct().getName()).isEqualTo("Hoja reforzada especial");
        }

        /** Y esa pieza ajena sigue llamándose igual: nunca se renombra. */
        @Test
        void anExistingEquivalentProductIsNeverRenamed() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));
            Product existing = equivalentProduct("NOMBRE HISTORICO");
            lenient().when(productRepository.findTargetProduct(any(), any(), any()))
                    .thenReturn(Optional.of(existing));

            transform(inputItem, request(Map.of("largo", 900), "Hoja reforzada especial"));

            assertThat(existing.getName()).isEqualTo("NOMBRE HISTORICO");
        }

        /**
         * Pedir dos veces el mismo nombre para la misma pieza no duplica el
         * catálogo: la que ya se llama así se reutiliza tal cual.
         */
        @Test
        void anEquivalentProductAlreadyNamedLikeThatIsReused() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));
            Product existing = equivalentProduct("Hoja reforzada especial");
            lenient().when(productRepository
                    .findTargetProductNamed(any(), any(), any(), any()))
                    .thenReturn(Optional.of(existing));

            transform(inputItem, request(Map.of("largo", 900), "Hoja reforzada especial"));

            verify(productRepository, org.mockito.Mockito.never()).save(any(Product.class));
        }

        /**
         * Copia parcial de otro producto: el resultado no es ninguno de los dos,
         * así que también es una pieza nueva y también admite nombre propio.
         *
         * Viaja como la envía el frontend —solo las características marcadas
         * dentro de `outputAttributes`, con `outputProduct` en null—, de modo que
         * la referencia no llega siquiera al manejador: aporta valores y nada
         * más. Aquí se copia su largo pero no su orificio.
         */
        @Test
        void aPartialReferenceCopyAlsoTakesTheCustomName() {
            InventoryItem inputItem = itemOf(attributes("largo", 1200, "tieneOrificio", false));
            Product reference = Product.builder()
                    .id(UUID.randomUUID())
                    .name("EJE DELANTERO COROLLA")
                    .family(family)
                    .attributes(attributes("largo", 900, "tieneOrificio", true))
                    .build();

            transform(inputItem, request(Map.of("largo", 900), "Hoja reforzada especial"));

            Product created = createdProduct();
            assertThat(created.getName()).isEqualTo("Hoja reforzada especial");
            assertThat(created.getAttributes())
                    .as("solo se copió el largo, no el orificio de la referencia")
                    .containsEntry("largo", 900)
                    .containsEntry("tieneOrificio", false);
            assertThat(reference.getName()).isEqualTo("EJE DELANTERO COROLLA");
        }

        /**
         * Y en el modo que sí manda un producto de salida, ese producto tampoco
         * se renombra: el nombre se aplica a la pieza nueva que resulta de
         * mezclarlo con la entrada.
         */
        @Test
        void theSelectedOutputProductIsNeverRenamed() {
            InventoryItem inputItem = itemOf(
                    attributes("largo", 1200, "tieneOrificio", false, "ancho", 80));
            Product selected = Product.builder()
                    .id(UUID.randomUUID())
                    .name("EJE DELANTERO COROLLA")
                    .family(family)
                    .attributes(attributes("largo", 900))
                    .build();
            lenient().when(productRepository.findById(selected.getId()))
                    .thenReturn(Optional.of(selected));

            transform(inputItem, new CreateTransformationRequestDTO(
                    UUID.randomUUID(), null, selected.getId(), null,
                    null, null, null, "Hoja reforzada especial"));

            assertThat(createdProduct().getName()).isEqualTo("Hoja reforzada especial");
            assertThat(selected.getName()).isEqualTo("EJE DELANTERO COROLLA");
        }

        private Product equivalentProduct(String name) {
            return Product.builder()
                    .id(UUID.randomUUID())
                    .name(name)
                    .family(family)
                    .attributes(attributes("largo", 900, "tieneOrificio", false))
                    .build();
        }
    }

    // ── Auxiliares ─────────────────────────────────────────────────────

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> productAttributes = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            productAttributes.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return productAttributes;
    }

    private InventoryItem itemOf(Map<String, Object> productAttributes) {
        Inventory inventory = Inventory.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .product(Product.builder()
                        .id(UUID.randomUUID())
                        .family(family)
                        .model(Model.builder().id(UUID.randomUUID()).build())
                        .attributes(productAttributes)
                        .build())
                .cost(SOURCE_COST)
                .price(SOURCE_PRICE)
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
            Map<String, Object> outputAttributes, String outputName) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, null, null,
                outputAttributes, null, null, outputName);
    }

    private Transformation transform(
            InventoryItem inputItem, CreateTransformationRequestDTO requestDTO) {
        Transformation transformation = Transformation.builder().build();
        handler.generate(transformation, inputItem, requestDTO);
        return transformation;
    }

    private List<Inventory> createdInventories() {
        ArgumentCaptor<Inventory> saved = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    private Product createdProduct() {
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(0);
    }

    private Optional<TransformationItem> itemWithRole(
            Transformation transformation, TransformationItemRole role) {
        return transformation.getItems()
                .stream()
                .filter(item -> item.getRole() == role)
                .findFirst();
    }
}
