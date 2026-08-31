package com.cns.spring_house.transformation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;

@ExtendWith(MockitoExtension.class)
class ContainerTransformationHandlerTest {

    @Mock
    private InventoryItemRepository itemRepository;

    /**
     * El libro de lotes: toda entrada o salida de stock deja ahora su lote. Se
     * simula porque estas pruebas miden la transformacion, no el FIFO.
     */
    @Mock
    private InventoryLotService lotService;

    @InjectMocks
    private ContainerTransformationHandler handler;

    /** Todas las unidades físicas creadas por la prueba, como una tabla. */
    private final List<InventoryItem> persistedItems = new ArrayList<>();

    /**
     * Reproduce `countByContainerId` igual que lo haría PostgreSQL: contando por
     * el lado propietario de la relación (`container_id`), no por la colección
     * en memoria. Así la prueba unitaria mide lo mismo que la base de datos.
     */
    @BeforeEach
    void simulateContainedItemsQuery() {
        lenient().when(itemRepository.countByContainerId(any()))
                .thenAnswer(invocation -> {
                    UUID containerId = invocation.getArgument(0);
                    return persistedItems.stream()
                            .filter(item -> item.getContainer() != null
                                    && containerId.equals(
                                            item.getContainer().getId()))
                            .count();
                });
    }

    private Inventory inventory(FamilyCategory category, int stock) {
        return Inventory.builder()
                .id(UUID.randomUUID())
                .stock(stock)
                .product(Product.builder()
                        .id(UUID.randomUUID())
                        .family(Family.builder()
                                .id(UUID.randomUUID())
                                .category(category)
                                .build())
                        .build())
                .build();
    }

    private InventoryItem itemIn(Inventory inventory, InventoryItemStatus status) {
        InventoryItem item = InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(status)
                .inventory(inventory)
                .build();
        inventory.addItem(item);
        persistedItems.add(item);
        return item;
    }

    /** Contenedor AVAILABLE con `count` componentes UNAVAILABLE dentro. */
    private InventoryItem assembledContainer(Inventory containerInventory,
            Inventory componentInventory, int count) {
        InventoryItem container = itemIn(containerInventory, InventoryItemStatus.AVAILABLE);
        for (int index = 0; index < count; index++) {
            InventoryItem component =
                    itemIn(componentInventory, InventoryItemStatus.UNAVAILABLE);
            container.addItem(component);
        }
        return container;
    }

    private List<TransformationItem> itemsWithRole(
            Transformation transformation, TransformationItemRole role) {
        return transformation.getItems().stream()
                .filter(item -> item.getRole() == role)
                .toList();
    }

    /** CONTAINER solo lee `containedItems`; los colochos no le aplican. */
    private CreateTransformationRequestDTO request(
            UUID outputProduct, List<UUID> containedItems) {
        return new CreateTransformationRequestDTO(
                UUID.randomUUID(), null, outputProduct, containedItems,
                null, null, null, null);
    }

    @Test
    void disassemblingRegistersOneInputAndOneReleasedPerComponent() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 4);
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, null));

        assertThat(itemsWithRole(transformation, TransformationItemRole.INPUT))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getItem()).isSameAs(container));
        assertThat(itemsWithRole(transformation, TransformationItemRole.RELEASED))
                .hasSize(4);
        assertThat(itemsWithRole(transformation, TransformationItemRole.OUTPUT))
                .isEmpty();
        assertThat(itemsWithRole(transformation, TransformationItemRole.REMAINDER))
                .isEmpty();
    }

    @Test
    void releasedComponentsBecomeAvailableAndLoseTheirContainer() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 2);
        List<InventoryItem> components =
                new ArrayList<>(container.getContainedItems());
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, null));

        assertThat(components).allSatisfy(component -> {
            assertThat(component.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
            // El estado actual queda limpio: la trazabilidad vive en la transformación.
            assertThat(component.getContainer()).isNull();
        });
        assertThat(container.getContainedItems()).isEmpty();
    }

    @Test
    void theContainerIsConsumedAndItsStockDecreases() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 3);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 2);
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, null));

        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(containerInventory.getStock()).isEqualTo(2);
    }

    @Test
    void everyComponentOfTheSameProductRestoresStock() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        // Los 4 componentes comparten inventario: el stock debe subir 4, no 1.
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 10);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 4);
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, null));

        assertThat(componentInventory.getStock()).isEqualTo(14);
    }

    @Test
    void releasingDoesNotDiscardTheComponentsThemselves() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 3);
        List<InventoryItem> components =
                new ArrayList<>(container.getContainedItems());
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, null));

        // Liberar no es borrar: las unidades siguen existiendo en su inventario
        // y son las mismas instancias referenciadas por los items RELEASED.
        assertThat(componentInventory.getItems()).containsAll(components);
        assertThat(itemsWithRole(transformation, TransformationItemRole.RELEASED))
                .map(TransformationItem::getItem)
                .containsExactlyInAnyOrderElementsOf(components);
    }

    @Test
    void anEmptyContainerStillRegistersItsInput() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 0);
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, null));

        assertThat(itemsWithRole(transformation, TransformationItemRole.INPUT)).hasSize(1);
        assertThat(itemsWithRole(transformation, TransformationItemRole.RELEASED)).isEmpty();
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
    }

    @Test
    void handlesTheContainerCategory() {
        assertThat(handler.getType()).isEqualTo(FamilyCategory.CONTAINER);
    }

    // ── Liberación selectiva ─────────────────────────────────────

    @Test
    void releasingOnlySomeComponentsLeavesTheRestInsideTheContainer() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 4);
        List<InventoryItem> all = new ArrayList<>(container.getContainedItems());
        InventoryItem first = all.get(0);
        InventoryItem third = all.get(2);
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container,
                request(null, List.of(first.getId(), third.getId())));

        assertThat(itemsWithRole(transformation, TransformationItemRole.RELEASED))
                .map(TransformationItem::getItem)
                .containsExactlyInAnyOrder(first, third);
        assertThat(first.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(first.getContainer()).isNull();
        assertThat(third.getContainer()).isNull();
        // Los no seleccionados siguen dentro y sin cambiar de estado.
        assertThat(all.get(1).getContainer()).isSameAs(container);
        assertThat(all.get(3).getContainer()).isSameAs(container);
        assertThat(all.get(1).getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(container.getContainedItems()).containsExactlyInAnyOrder(all.get(1), all.get(3));
        // Solo sube el stock de lo liberado.
        assertThat(componentInventory.getStock()).isEqualTo(2);
    }

    /**
     * La maleta física sigue existiendo mientras le quede una sola hoja dentro,
     * así que todavía no puede desaparecer del stock del contenedor.
     */
    @Test
    void aPartialReleaseDoesNotDiscountTheContainerStock() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 2);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 4);
        List<InventoryItem> all = new ArrayList<>(container.getContainedItems());
        InventoryItem released = all.get(0);

        handler.generate(Transformation.builder().build(), container,
                request(null, List.of(released.getId())));

        assertThat(containerInventory.getStock()).isEqualTo(2);
        assertThat(container.getStatus()).isNotEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(container.getContainedItems())
                .containsExactlyInAnyOrder(all.get(1), all.get(2), all.get(3));
        assertThat(released.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(componentInventory.getStock()).isEqualTo(1);
    }

    /**
     * Recorrido completo del desarmado por tandas: el stock del contenedor no se
     * mueve hasta que sale la última hoja, y entonces baja exactamente una vez.
     */
    @Test
    void theContainerStockOnlyDropsWhenTheLastComponentLeaves() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 2);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 4);
        List<InventoryItem> all = new ArrayList<>(container.getContainedItems());

        for (int index = 0; index < 3; index++) {
            handler.generate(Transformation.builder().build(), container,
                    request(null, List.of(all.get(index).getId())));

            // Todavía queda contenido: la maleta sigue en pie y el stock intacto.
            assertThat(container.getStatus())
                    .isEqualTo(InventoryItemStatus.UNAVAILABLE);
            assertThat(container.getContainedItems()).hasSize(3 - index);
            assertThat(containerInventory.getStock()).isEqualTo(2);
        }

        handler.generate(Transformation.builder().build(), container,
                request(null, List.of(all.get(3).getId())));

        assertThat(container.getContainedItems()).isEmpty();
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(containerInventory.getStock()).isEqualTo(1);
        assertThat(componentInventory.getStock()).isEqualTo(4);
    }

    /** Última hoja de una maleta ya empezada: ahí sí baja el stock. */
    @Test
    void releasingTheLastRemainingComponentConsumesTheContainer() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 2);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 1);
        // La maleta ya venía a medio desarmar de una transformación anterior.
        container.setStatus(InventoryItemStatus.UNAVAILABLE);
        InventoryItem last = container.getContainedItems().get(0);

        handler.generate(Transformation.builder().build(), container,
                request(null, List.of(last.getId())));

        assertThat(container.getContainedItems()).isEmpty();
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(containerInventory.getStock()).isEqualTo(1);
        assertThat(last.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(last.getContainer()).isNull();
    }

    /** "Liberar toda la maleta" en una sola petición: un único descuento. */
    @Test
    void releasingEveryComponentAtOnceDiscountsTheContainerStockOnce() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 2);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 4);
        List<InventoryItem> all = new ArrayList<>(container.getContainedItems());

        handler.generate(Transformation.builder().build(), container, request(null, null));

        assertThat(containerInventory.getStock()).isEqualTo(1);
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(all).allSatisfy(component -> assertThat(component.getStatus())
                .isEqualTo(InventoryItemStatus.AVAILABLE));
        assertThat(componentInventory.getStock()).isEqualTo(4);
    }

    @Test
    void selectingEveryComponentBehavesLikeAFullRelease() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 1);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 3);
        List<UUID> everyId = container.getContainedItems().stream()
                .map(InventoryItem::getId)
                .toList();
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container, request(null, everyId));

        assertThat(itemsWithRole(transformation, TransformationItemRole.RELEASED)).hasSize(3);
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.CONSUMED);
        assertThat(containerInventory.getStock()).isZero();
    }

    @Test
    void aComponentFromAnotherContainerIsRejected() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 2);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 2);
        InventoryItem otherContainer =
                assembledContainer(containerInventory, componentInventory, 1);
        InventoryItem foreign = otherContainer.getContainedItems().get(0);

        assertThatThrownBy(() -> handler.generate(
                Transformation.builder().build(), container,
                request(null, List.of(foreign.getId()))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no pertenece a esta unidad contenedora");

        // Nada se mueve: ni estados, ni relaciones, ni stock.
        assertThat(foreign.getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(foreign.getContainer()).isSameAs(otherContainer);
        assertThat(container.getContainedItems()).hasSize(2);
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.AVAILABLE);
        assertThat(containerInventory.getStock()).isEqualTo(2);
        assertThat(componentInventory.getStock()).isZero();
    }

    @Test
    void aDuplicatedComponentIdReleasesTheUnitOnlyOnce() {
        Inventory containerInventory = inventory(FamilyCategory.CONTAINER, 2);
        Inventory componentInventory = inventory(FamilyCategory.SPECIAL, 0);
        InventoryItem container =
                assembledContainer(containerInventory, componentInventory, 2);
        InventoryItem first = container.getContainedItems().get(0);
        Transformation transformation = Transformation.builder().build();

        handler.generate(transformation, container,
                request(null, List.of(first.getId(), first.getId())));

        assertThat(itemsWithRole(transformation, TransformationItemRole.RELEASED)).hasSize(1);
        // El stock no puede subir dos veces por la misma unidad física.
        assertThat(componentInventory.getStock()).isEqualTo(1);
        // Y repetir el id tampoco puede "vaciar" la maleta antes de tiempo.
        assertThat(container.getContainedItems()).hasSize(1);
        assertThat(container.getStatus()).isEqualTo(InventoryItemStatus.UNAVAILABLE);
        assertThat(containerInventory.getStock()).isEqualTo(2);
    }

}
