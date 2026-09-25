package com.cns.spring_house.inventory.handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ContainerInventoryHandler
        implements InventoryHandler {

    private final InventoryRepository inventoryRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryLotService lotService;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.CONTAINER;
    }

    /**
     * Armar una maleta NO es una compra: sus componentes ya eran del taller y no
     * entra mercaderia nueva. Por eso `context` no se usa aqui —no hay proveedor
     * que anotar— y el lote que se crea es de origen PRODUCTION, sin costo.
     *
     * El costo de esos componentes no se traslada al contenedor. Hacerlo
     * exigiria un modelo de costeo de manufactura que este sistema no tiene, y
     * repartirlo a ojo produciria una cifra que nadie podria defender. La
     * consecuencia se declara con honestidad: una maleta vendida no aporta costo
     * historico, y el panel la cuenta como linea sin cobertura en vez de
     * inventarle uno.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<InventoryItem> addStock(
            Inventory containerInventory,
            AddStockRequestDTO requestDTO,
            StockEntryContext context) {

        List<UUID> containedProductIds =
                requestDTO.containedItems();

        if (containedProductIds == null
                || containedProductIds.isEmpty()) {
            throw new ValidationException(
                    "Es necesaria una lista de subproductos");
        }

        UUID workshopId =
                SecurityUtils.getWorkshopId();

        /*
         * Cada ID representa una unidad física.
         *
         * Por ejemplo:
         * [productoA, productoA, productoB]
         *
         * significa:
         * productoA -> 2 unidades
         * productoB -> 1 unidad
         */
        Map<UUID, Integer> requiredQuantities =
                containedProductIds.stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                ignored -> 1,
                                Integer::sum,
                                LinkedHashMap::new));

        /*
         * Busca y bloquea los inventarios de los componentes
         * pertenecientes al taller autenticado.
         */
        List<Inventory> componentInventories =
                inventoryRepository
                        .findAllByProductIdInAndWorkshopId(
                                requiredQuantities.keySet(),
                                workshopId);

        Map<UUID, Inventory> inventoryByProduct =
                componentInventories.stream()
                        .collect(Collectors.toMap(
                                inventory ->
                                        inventory.getProduct()
                                                .getId(),
                                Function.identity()));

        /*
         * Primero se validan y seleccionan todas las unidades.
         *
         * Todavía no se cambia el estado ni el stock para evitar
         * dejar una operación parcialmente modificada si algún
         * componente no cumple las reglas.
         */
        Map<Inventory, List<InventoryItem>> selectedItems =
                new LinkedHashMap<>();

        for (Map.Entry<UUID, Integer> requirement
                : requiredQuantities.entrySet()) {

            UUID productId =
                    requirement.getKey();

            int requiredUnits =
                    requirement.getValue();

            Inventory componentInventory =
                    inventoryByProduct.get(productId);

            if (componentInventory == null) {
                throw new ValidationException(
                        "El producto "
                                + productId
                                + " no tiene inventario "
                                + "en el taller actual");
            }

            validateSpecialCategory(
                    componentInventory);

            validateSameModel(
                    containerInventory,
                    componentInventory);

            validateStock(
                    componentInventory,
                    requiredUnits);

            /*
             * Obtiene unidades físicas AVAILABLE y las bloquea
             * para que otra transacción no pueda utilizarlas
             * simultáneamente.
             */
            List<InventoryItem> availableItems =
                    itemRepository.findItemsForUpdate(
                            componentInventory.getId(),
                            InventoryItemStatus.AVAILABLE,
                            PageRequest.of(
                                    0,
                                    requiredUnits));

            if (availableItems.size() < requiredUnits) {
                throw new ValidationException(
                        "No existen suficientes unidades "
                                + "físicas disponibles para el producto: "
                                + componentInventory
                                .getProduct()
                                .getName()
                                + ". Disponibles: "
                                + availableItems.size()
                                + ", requeridas: "
                                + requiredUnits);
            }

            selectedItems.put(
                    componentInventory,
                    availableItems);
        }

        /*
         * Crea una unidad física nueva del producto CONTAINER,
         * por ejemplo una maleta de resortes.
         */
        InventoryItem containerItem =
                InventoryItem.builder()
                        .status(
                                InventoryItemStatus.AVAILABLE)
                        .build();

        /*
         * Establece la relación:
         *
         * containerItem.inventory = containerInventory
         */
        containerInventory.addItem(
                containerItem);

        /*
         * IMPORTANTE:
         *
         * La unidad física del contenedor debe persistirse
         * antes de que las hojas existentes puedan apuntar a
         * ella mediante InventoryItem.container.
         *
         * Esto evita TransientPropertyValueException.
         */
        containerItem =
                itemRepository.saveAndFlush(
                        containerItem);

        /*
         * Vincula las unidades físicas SPECIAL existentes
         * con el contenedor ya persistido.
         */
        for (Map.Entry<Inventory, List<InventoryItem>> entry
                : selectedItems.entrySet()) {

            Inventory componentInventory =
                    entry.getKey();

            List<InventoryItem> items =
                    entry.getValue();

            for (InventoryItem item : items) {

                /*
                 * La unidad ya no se encuentra disponible
                 * individualmente porque ahora forma parte
                 * del contenedor.
                 */
                item.setStatus(
                        InventoryItemStatus.UNAVAILABLE);

                /*
                 * Ejecuta internamente:
                 *
                 * item.setContainer(containerItem);
                 */
                containerItem.addItem(item);
            }

            /*
             * Reduce únicamente el stock disponible del
             * producto SPECIAL utilizado.
             */
            componentInventory.removeStock(
                    items.size());

            /*
             * Y consume sus lotes en el mismo orden FIFO que una venta.
             *
             * Sin esto, el componente saldria de las existencias pero su lote
             * seguiria contando remanente, y el inventario acabaria con menos
             * stock que respaldo: la siguiente venta de ese producto costearia
             * contra unidades que ya no existen. No lleva traza de asignacion
             * porque no hay linea de venta a la que atribuir el costo.
             */
            lotService.consumeWithoutTrace(
                    componentInventory,
                    items.size());
        }

        /*
         * Cada petición crea exactamente una unidad física
         * nueva del contenedor.
         */
        containerInventory.addStock(1);

        /*
         * La unidad nueva necesita su lote para que el contenedor cumpla el
         * mismo invariante que el resto del inventario. Es PRODUCTION: nació de
         * un armado, no de una compra.
         */
        lotService.registerProduction(containerInventory, 1);
        return List.of(containerItem);
    }

    private void validateSpecialCategory(
            Inventory inventory) {

        FamilyCategory category =
                inventory.getProduct()
                        .getFamily()
                        .getCategory();

        if (category != FamilyCategory.SPECIAL) {
            throw new ValidationException(
                    "El producto "
                            + inventory.getProduct()
                            .getName()
                            + " no pertenece a una familia SPECIAL");
        }
    }

    /**
     * Una maleta solo puede armarse con hojas de su mismo modelo de vehículo.
     * El filtrado del frontend no basta: un id de producto manipulado llegaría
     * igual, así que la compatibilidad se valida aquí. La comparación es
     * null-safe, de modo que un contenedor sin modelo solo admite componentes
     * sin modelo.
     */
    private void validateSameModel(
            Inventory containerInventory,
            Inventory componentInventory) {

        UUID containerModelId = modelIdOf(containerInventory);
        UUID componentModelId = modelIdOf(componentInventory);

        if (!Objects.equals(containerModelId, componentModelId)) {
            throw new ValidationException(
                    "El componente "
                            + componentInventory.getProduct().getName()
                            + " no corresponde al modelo del contenedor");
        }
    }

    private UUID modelIdOf(Inventory inventory) {
        Model model = inventory.getProduct().getModel();
        return model == null ? null : model.getId();
    }

    private void validateStock(
            Inventory inventory,
            int requiredUnits) {

        Integer stock =
                inventory.getStock();

        if (stock == null
                || stock < requiredUnits) {
            throw new ValidationException(
                    "No hay suficiente stock para el producto: "
                            + inventory.getProduct()
                            .getName()
                            + ". Disponible: "
                            + (stock == null ? 0 : stock)
                            + ", requerido: "
                            + requiredUnits);
        }
    }
}