package com.cns.spring_house.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;

import jakarta.persistence.LockModeType;

public interface InventoryItemRepository
        extends JpaRepository<InventoryItem, UUID> {

    int countByInventoryAndStatus(
            Inventory inventory,
            InventoryItemStatus status);

    /**
     * Item acotado al taller del usuario. Buscar solo por id permitia vender
     * manualmente una unidad del inventario de otro taller.
     */
    Optional<InventoryItem> findByIdAndInventoryWorkshopId(
            UUID id,
            UUID workshopId);

    /**
     * Unidades físicas que siguen dentro de un contenedor concreto. Consulta el
     * lado propietario de la relación (`container_id`), que es el único que
     * refleja la verdad tras liberar componentes.
     */
    long countByContainerId(UUID containerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT item
        FROM InventoryItem item
        WHERE item.inventory.id = :inventoryId
          AND item.status = :status
        ORDER BY item.createdAt ASC
    """)
    List<InventoryItem> findItemsForUpdate(
            @Param("inventoryId") UUID inventoryId,
            @Param("status") InventoryItemStatus status,
            Pageable pageable);
}