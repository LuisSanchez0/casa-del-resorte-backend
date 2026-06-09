package com.cns.spring_house.inventory.handler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryRepository;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ContainerInventoryHandler implements InventoryHandler {

    private final InventoryRepository repository;

    @Override
    public FamilyCategory getType() {
        return FamilyCategory.CONTAINER;
    }

    @Override
    public void addStock(Inventory inventory, AddStockRequestDTO requestDTO) {
        if (requestDTO.containedItems() == null || requestDTO.containedItems().isEmpty()) {
            throw new ValidationException(
                    "Es necesaria una lista de subproductos");
        }
        InventoryItem item = InventoryItem.builder()
                .status(InventoryItemStatus.AVAILABLE)
                .build();
        inventory.addItem(item);
        inventory.addStock(1);
        addContainedItems(item, requestDTO.containedItems());
    }

    private void addContainedItems(InventoryItem container, List<UUID> containedItems) {
        UUID workshopId = SecurityUtils.getWorkshopId();
        Map<UUID, Inventory> inventories = repository
                .findAllByProductIdInAndWorkshopId(containedItems, workshopId)
                .stream()
                .collect(Collectors.toMap(
                        inventory -> inventory.getProduct().getId(),
                        Function.identity()));
        containedItems.forEach(item -> {
            Inventory inventory = inventories.get(item);
            if (inventory != null && isSpecialCategory(inventory)) {
                InventoryItem inventoryItem = InventoryItem.builder()
                            .status(InventoryItemStatus.UNAVAILABLE)
                            .build();
                container.addItem(inventoryItem);
                inventory.addItem(inventoryItem);
            }
        });
    }

    private boolean isSpecialCategory(Inventory inventory) {
        FamilyCategory category = inventory.getProduct().getFamily().getCategory();
        return category == FamilyCategory.SPECIAL;
    }

}
