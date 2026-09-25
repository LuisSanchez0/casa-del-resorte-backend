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

    /**
     * Unidades FISICAS que todavia pueden operarse, contadas por inventario y
     * para TODO el taller de una vez.
     *
     * Replica exactamente el predicado que decide si una unidad puede entrar en
     * una transformacion (`TransformationServiceImpl.isProcessable`): disponible,
     * o una maleta a medio desarmar que aun guarda componentes. No se cuenta
     * `inventory.stock`, que es un contador con su propia vida: un contenedor
     * parcialmente desarmado sigue sumando existencia aunque ya no sea vendible,
     * y un inventario puede conservar su fila con todas sus unidades consumidas.
     *
     * Es UNA consulta con `GROUP BY` y un `EXISTS`, no una por inventario: la
     * pantalla de transformaciones lista decenas de inventarios y preguntar por
     * cada uno seria un N+1.
     *
     * Devuelve solo los inventarios con al menos una unidad: los que no aparecen
     * cuentan cero.
     */
    @Query("""
        SELECT item.inventory.id, COUNT(item)
        FROM InventoryItem item
        WHERE item.inventory.workshop.id = :workshopId
          AND (
                item.status = com.cns.spring_house.inventory.entity.InventoryItemStatus.AVAILABLE
                OR (
                     item.status = com.cns.spring_house.inventory.entity.InventoryItemStatus.UNAVAILABLE
                     AND item.inventory.product.family.category
                         = com.cns.spring_house.family.entity.FamilyCategory.CONTAINER
                     AND EXISTS (
                           SELECT 1 FROM InventoryItem child
                           WHERE child.container = item)
                   )
              )
        GROUP BY item.inventory.id
    """)
    List<Object[]> countProcessableUnitsByWorkshop(@Param("workshopId") UUID workshopId);

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