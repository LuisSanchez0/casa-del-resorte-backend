package com.cns.spring_house.inventory.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
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
public class Inventory extends Auditable {

    @Id
    @UuidGenerator
    private UUID id;
    @ManyToOne
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;
    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;
    private BigDecimal price;
    private BigDecimal cost;
    private Integer minThreshold;
    private Boolean isBundle;
    @OneToOne(
            mappedBy = "inventory",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private QuantityInventory quantity;
    @Builder.Default
    @OneToMany(
            mappedBy = "inventory",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<UnitInventory> units = new ArrayList<>();

    public void setQuantity(QuantityInventory quantity) {
        this.quantity = quantity;
        quantity.setInventory(this);
    }

    public void addUnit(UnitInventory unit) {
        this.units.add(unit);
        unit.setInventory(this);
    }

}
