package com.cns.spring_house.transformation.handler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ContainerTransformationHandler implements TransformationHandler {

    private final InventoryItemRepository itemRepository;
    private final InventoryLotService lotService;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.CONTAINER;
    }

    /**
     * Desarma el contenedor dentro de la transacción del servicio: o se aplica
     * todo, o no se aplica nada.
     *
     * `containedItems` nulo o vacío = desarmado completo (contrato original).
     * Con contenido, se liberan únicamente esas unidades físicas concretas.
     *
     * Cada componente liberado se registra como RELEASED, de modo que la
     * trazabilidad viva en la transformación mientras que `container` sigue
     * respondiendo solo dónde está la unidad AHORA.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public Set<UUID> generate(
                Transformation transformation,
                InventoryItem inputItem,
                CreateTransformationRequestDTO requestDTO) {
        List<InventoryItem> components = resolveComponents(
                inputItem, requestDTO.containedItems());
        ensureSameWorkshop(inputItem, components);

        for (InventoryItem component : components) {
            inputItem.releaseItem(component);
            component.setStatus(InventoryItemStatus.AVAILABLE);
            component.getInventory().addStock(1);
            // El componente vuelve al inventario individual, asi que necesita un
            // lote que respalde esa existencia. Entra como PRODUCTION y NO
            // recuperando el lote que consumio al armarse: aquel se consumio sin
            // traza —no habia linea de venta a la que atribuirlo— y adivinar
            // cual era repondria en el lote equivocado. Se declara la
            // consecuencia: un componente que se armo y se volvio a soltar
            // pierde su costo de compra original.
            lotService.registerProduction(component.getInventory(), 1);
            transformation.addItem(TransformationItem.builder()
                    .item(component)
                    .role(TransformationItemRole.RELEASED)
                    .build());
        }

        transformation.addItem(processInputItem(inputItem));
        return Set.of(); // Released components already had physical identities.
    }

    /**
     * Ningún componente puede colgar de un inventario distinto al de la maleta.
     *
     * El taller NO se vuelve a leer del token aquí: el servicio ya acotó la
     * maleta al taller autenticado antes de llamar, así que exigir que cada
     * componente comparta el taller de SU PROPIO contenedor es exactamente la
     * misma garantía, y deja la lectura del JWT en un único sitio. El manejador
     * comprueba una invariante estructural; el límite entre talleres lo pone el
     * servicio.
     *
     * Se comprueba porque el esquema NO lo garantiza: `container_id` es una
     * clave ajena a `inventory_item` a secas, y cada unidad cuelga de su propio
     * `inventory_id`, de modo que nada en la base impide una fila con un
     * contenedor de un taller y un inventario de otro. Liberar una unidad así le
     * sumaría existencias —y un lote de producción— al inventario ajeno.
     *
     * No añade consultas: `Inventory.workshop` es `@ManyToOne` EAGER, y el
     * inventario de cada componente ya se carga unas líneas más abajo para
     * sumarle stock y registrarle la producción.
     */
    private void ensureSameWorkshop(
            InventoryItem inputItem, List<InventoryItem> components) {
        UUID workshopId = workshopOf(inputItem);

        for (InventoryItem component : components) {
            // Se comparan los DOS, no se exige que existan: en la base
            // `inventory.workshop_id` es NOT NULL (V5), asi que en datos reales
            // ambos estan siempre puestos y la comprobacion es estricta.
            if (!Objects.equals(workshopId, workshopOf(component))) {
                // No se nombra ningún taller: se dice que el componente no sirve
                // para esta operación, igual que en el resto del flujo.
                throw new ValidationException(
                        "El componente seleccionado no pertenece a esta unidad contenedora");
            }
        }
    }

    private UUID workshopOf(InventoryItem item) {
        Inventory inventory = item.getInventory();
        if (inventory == null || inventory.getWorkshop() == null) {
            return null;
        }
        return inventory.getWorkshop().getId();
    }

    /**
     * Resuelve qué unidades se liberan validando contra el contenido real del
     * contenedor. Nunca se confía en el cliente: un id de otro contenedor o
     * inexistente se rechaza, y los duplicados se descartan para no sumar stock
     * dos veces por la misma unidad.
     */
    private List<InventoryItem> resolveComponents(
            InventoryItem inputItem, List<UUID> requestedItems) {
        List<InventoryItem> contained = new ArrayList<>(inputItem.getContainedItems());

        if (requestedItems == null || requestedItems.isEmpty()) {
            return contained;
        }

        Set<UUID> seen = new HashSet<>();
        List<InventoryItem> selected = new ArrayList<>();

        for (UUID requestedId : requestedItems) {
            if (requestedId == null) {
                throw new ValidationException(
                        "La lista de componentes contiene un identificador vacío");
            }
            if (!seen.add(requestedId)) {
                // Duplicado: se ignora en lugar de liberar y sumar stock dos veces.
                continue;
            }
            InventoryItem component = contained.stream()
                    .filter(item -> requestedId.equals(item.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ValidationException(
                            "El componente seleccionado no pertenece a esta unidad contenedora"));
            selected.add(component);
        }

        if (selected.isEmpty()) {
            throw new ValidationException(
                    "Debes seleccionar al menos un componente para liberar");
        }

        return selected;
    }

    /**
     * Estado persistido del contenedor, decidido SIEMPRE con lo que queda
     * dentro después de soltar lo seleccionado:
     *
     * - Si todavía quedan componentes, la maleta física sigue existiendo: queda
     *   UNAVAILABLE (ya no es una unidad ensamblada vendible, pero sigue siendo
     *   procesable para liberar el resto) y el stock NO se toca.
     * - Solo cuando sale el último componente pasa a CONSUMED y recién ahí se
     *   descuenta la unidad del inventario del contenedor.
     *
     * El descuento ocurre exactamente una vez: se aplica únicamente en la
     * transición a CONSUMED, y una unidad CONSUMED ya no vuelve a ser
     * procesable (ver TransformationServiceImpl#isProcessable).
     */
    private TransformationItem processInputItem(InventoryItem inputItem) {
        if (remainingComponents(inputItem) > 0) {
            inputItem.setStatus(InventoryItemStatus.UNAVAILABLE);
        } else if (inputItem.getStatus() != InventoryItemStatus.CONSUMED) {
            inputItem.setStatus(InventoryItemStatus.CONSUMED);
            inputItem.getInventory().removeStock(1);
            // La maleta deja de existir como unidad vendible: su lote se
            // consume, igual que si se hubiera vendido.
            lotService.consumeWithoutTrace(inputItem.getInventory(), 1);
        }

        return TransformationItem.builder()
                .item(inputItem)
                .role(TransformationItemRole.INPUT)
                .build();
    }

    /**
     * Cuenta las unidades que siguen dentro de esta maleta física concreta
     * después de liberar las seleccionadas.
     *
     * Se pregunta a la base de datos en lugar de mirar `containedItems`: esa
     * colección es el lado inverso de la relación y la baja real la escribe
     * `container_id`, así que primero se vuelca el cambio y luego se cuenta.
     * El flush no confirma nada: sigue dentro de la transacción del servicio y
     * cualquier fallo posterior lo revierte todo.
     *
     * Un contenedor todavía sin id no existe en la tabla, y contar por
     * `container_id = null` traería las unidades sueltas de todo el taller, así
     * que ese caso se resuelve en memoria.
     */
    private long remainingComponents(InventoryItem inputItem) {
        if (inputItem.getId() == null) {
            return inputItem.getContainedItems().size();
        }

        itemRepository.flush();

        return itemRepository.countByContainerId(inputItem.getId());
    }

}
