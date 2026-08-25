package com.cns.spring_house.inventory.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
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

/**
 * El lote de carga es para los items CONTENIDOS de un contenedor vendido.
 *
 * Esas unidades llegan por la coleccion `containedItems`, que ya se acota por
 * lotes, pero el mapeo baja despues al inventario de CADA una para su producto y
 * sus caracteristicas. Sin lote, una venta de veinte maletas de tres piezas
 * gastaba sesenta consultas a `inventory`: una por pieza. Con el, Hibernate
 * resuelve todos esos proxies con un `id IN (...)`.
 *
 * No sustituye al grafo de la venta: las lineas propias de la factura siguen
 * trayendo su inventario por JOIN. Esto cubre lo que cuelga de una coleccion, que
 * es justo lo que no puede unirse sin multiplicar filas.
 */
@BatchSize(size = 100)
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

    @Builder.Default
    private Integer stock = 0;

    private Integer minStock;
    @Builder.Default
    @OneToMany(
            mappedBy = "inventory",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<InventoryItem> items = new ArrayList<>();

    public void addStock(int units) {
        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades deben ser mayores que cero");
        }

        if (this.stock == null) {
            this.stock = 0;
        }

        this.stock += units;
    }

    public void removeStock(int units) {
        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades deben ser mayores que cero");
        }

        if (this.stock == null || units > this.stock) {
            throw new ValidationException(
                    "No hay suficiente stock disponible");
        }

        this.stock -= units;
    }

    public void addItem(InventoryItem item) {
        this.items.add(item);
        item.setInventory(this);
    }

}
