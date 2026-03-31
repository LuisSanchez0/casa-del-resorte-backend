package com.cns.spring_house.inventory.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InventoryItem extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id")
    private Inventory inventory;
    @Enumerated(EnumType.STRING)
    private InventoryItemStatus status;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "container_id")
    private InventoryItem container;
    @Builder.Default
    @OneToMany(mappedBy = "container")
    private List<InventoryItem> containedItems = new ArrayList<>();

    public void addItem(InventoryItem item) {
        this.containedItems.add(item);
        item.setContainer(this);
    }

}
