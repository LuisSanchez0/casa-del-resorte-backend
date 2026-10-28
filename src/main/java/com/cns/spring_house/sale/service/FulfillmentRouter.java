package com.cns.spring_house.sale.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cns.spring_house.acquisition.service.InventoryLotService;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.sale.entity.Fulfillment;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.entity.FulfillmentType;
import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * Manda al taller, al enviar la comanda, cada solicitud pendiente.
 *
 * La ruta de una pieza especial se decide al AGREGARLA (ver
 * {@link SaleRequestLines#addSpecial}): la unidad de Control ya esta reservada
 * y aqui solo se pone en cola, sin volver a reservar ni a descontar nada. Un
 * servicio va al Operador.
 *
 * Una pieza agregada antes de reservar al agregar llega sin ruta; esa se decide
 * aqui como antes, con el inventario BLOQUEADO y el stock de este instante:
 * <ul>
 * <li>con una unidad DISPONIBLE del producto pedido: a Control, con esa unidad
 * reservada para esta venta;</li>
 * <li>sin unidades disponibles —o sin suficientes—: al Operador, para
 * fabricarla.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class FulfillmentRouter {

    private final InventoryRepository inventoryRepository;
    private final InventoryItemRepository itemRepository;
    private final InventoryLotService lotService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void route(List<SaleDetail> pending, LocalDateTime now) {
        // Agrupadas por producto y en orden de id: dos envios simultaneos que
        // pidan los mismos productos bloquean sus inventarios en el MISMO orden
        // y no pueden esperarse en circulo.
        Map<UUID, List<SaleDetail>> specials = new TreeMap<>();
        for (SaleDetail detail : pending) {
            FulfillmentType decided = detail.getFulfillment().getType();
            if (decided != null) {
                // Ruta y unidad decididas al agregarla: enviar no reserva otra vez.
                queue(detail, decided, now);
            } else if (isSpecial(detail)) {
                specials.computeIfAbsent(detail.getProduct().getId(),
                        ignored -> new ArrayList<>()).add(detail);
            } else {
                queue(detail, FulfillmentType.SERVICE, now);
            }
        }
        specials.forEach((productId, details) -> routeSpecial(productId, details, now));
    }

    private void routeSpecial(UUID productId, List<SaleDetail> details, LocalDateTime now) {
        Optional<Inventory> inventory = inventoryRepository
                .findByProductIdAndWorkshopIdForUpdate(
                        productId, SecurityUtils.getWorkshopId());
        // Las unidades libres se leen bloqueadas y de la mas antigua a la mas
        // nueva. "Disponible" ya deja fuera lo reservado por otra venta (SOLD)
        // y los componentes metidos en una maleta (UNAVAILABLE).
        List<InventoryItem> free = inventory
                .map(found -> itemRepository.findItemsForUpdate(
                        found.getId(),
                        InventoryItemStatus.AVAILABLE,
                        PageRequest.of(0, details.size())))
                .orElse(List.of());

        for (int index = 0; index < details.size(); index++) {
            SaleDetail detail = details.get(index);
            if (index < free.size()) {
                reserve(detail, free.get(index), inventory.get(), now);
            } else {
                queue(detail, FulfillmentType.TRANSFORMATION, now);
            }
        }
    }

    /**
     * La reserva es la de siempre: la unidad pasa a SOLD, sale del stock y su
     * costo se toma del lote FIFO en este mismo instante. Es exactamente lo que
     * hacia agregar una pieza especial al carrito; solo cambia el momento.
     * Control puede despues entregar otra unidad equivalente en su lugar.
     */
    private void reserve(
            SaleDetail detail, InventoryItem item, Inventory inventory, LocalDateTime now) {
        item.setStatus(InventoryItemStatus.SOLD);
        inventory.removeStock(1);
        detail.setItem(item);
        lotService.consumeForSaleDetail(detail, inventory, 1);
        queue(detail, FulfillmentType.STOCK_RELEASE, now);
    }

    private void queue(SaleDetail detail, FulfillmentType type, LocalDateTime now) {
        Fulfillment fulfillment = detail.getFulfillment();
        fulfillment.setType(type);
        fulfillment.setStatus(FulfillmentStatus.QUEUED);
        fulfillment.setDispatchedAt(now);
    }

    private boolean isSpecial(SaleDetail detail) {
        return detail.getProduct().getFamily().getCategory() == FamilyCategory.SPECIAL;
    }

}
