    package com.cns.spring_house.inventory.repository;

    import java.util.UUID;

    import org.springframework.data.jpa.repository.JpaRepository;

    import com.cns.spring_house.inventory.entity.Inventory;
    import com.cns.spring_house.inventory.entity.InventoryItem;
    import com.cns.spring_house.inventory.entity.InventoryItemStatus;

    public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

        int countByInventoryAndStatus(Inventory inventory, InventoryItemStatus status);

    }
