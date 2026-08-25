package com.cns.spring_house.inventory.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
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
    /**
     * Lote de carga: `containedItems` es una COLECCION, asi que no puede unirse
     * en la misma consulta que las lineas de una venta sin multiplicar sus filas.
     * Al quedarse fuera del grafo, el mapeo la pedia por cada unidad, y una venta
     * de veinte contenedores gastaba cien lecturas de `inventory_item`.
     *
     * Con el lote, Hibernate resuelve hasta {@code size} colecciones en UNA
     * consulta por `container_id IN (...)`, de modo que el coste deja de ser una
     * consulta por unidad sin que ninguna fila se duplique.
     */
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "container")
    private List<InventoryItem> containedItems = new ArrayList<>();

    public void addItem(InventoryItem item) {
        this.containedItems.add(item);
        item.setContainer(this);
    }

    /**
     * Libera un componente de este contenedor manteniendo sincronizados ambos
     * lados de la relación.
     *
     * `containedItems` es el lado inverso (mappedBy) y no declara cascade ni
     * orphanRemoval, por lo que quitarlo de la colección NO borra el
     * InventoryItem: la baja real de la relación la produce poner en null el
     * lado propietario (columna container_id). Liberar no es eliminar.
     */
    public void releaseItem(InventoryItem item) {
        this.containedItems.remove(item);
        item.setContainer(null);
    }

}
