package com.cns.spring_house.acquisition.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.supplier.entity.Supplier;
import com.cns.spring_house.workshop.entity.Workshop;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Un LOTE de inventario: una entrada de stock, con lo que costo y lo que queda
 * sin consumir.
 *
 * Es la fuente de verdad HISTORICA del costo. {@code Inventory.cost} sigue
 * existiendo como costo de referencia actual, pero es mutable —cada compra lo
 * pisa—, asi que no puede responder cuanto costo la mercaderia que se vendio el
 * mes pasado. Este lote si: su {@code unitCost} no se vuelve a tocar jamas.
 *
 * {@code quantityRemaining} es lo unico que cambia despues del alta, y solo por
 * dos caminos simetricos: el FIFO lo baja al vender y la restauracion lo sube al
 * quitar, anular o revertir. Los dos pasan por
 * {@code InventoryLotService}, que es quien toma el cerrojo de fila.
 *
 * Los invariantes viven en el ESQUEMA
 * ({@code ck_inventory_acquisition_remaining} y
 * {@code ck_inventory_acquisition_origin_shape}) y no solo aqui: los metodos de
 * esta clase dan un mensaje entendible, pero lo que sostiene el modelo cuando
 * dos ventas simultaneas tocan el mismo lote es el CHECK de PostgreSQL.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InventoryAcquisition extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workshop_id")
    private Workshop workshop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id")
    private Inventory inventory;

    /** Null en un lote {@code PRODUCTION}: esa unidad no la vendio nadie. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /** Quien registro la entrada. Null cuando la produce el sistema. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Enumerated(EnumType.STRING)
    private AcquisitionOrigin origin;

    /** Null en un lote {@code PRODUCTION}. Ver {@link AcquisitionOrigin}. */
    @Enumerated(EnumType.STRING)
    private AcquisitionType acquisitionType;

    private Integer quantityReceived;

    private Integer quantityRemaining;

    /** Null en un lote {@code PRODUCTION}: no hay costo de compra que anotar. */
    private BigDecimal unitCost;

    /**
     * Fecha de negocio de la entrada. La pone el servidor con el mismo reloj que
     * el resto de sellos financieros; el cliente no la envia. Es la fecha por la
     * que se agrupan las graficas de gasto en compras, no
     * {@code Inventory.updatedAt} —que se mueve con cualquier edicion— ni la
     * fecha de la venta.
     */
    private LocalDateTime acquiredAt;

    /**
     * Consume unidades de este lote. Devuelve cuantas pudo consumir de verdad,
     * que puede ser menos de las pedidas cuando el lote se agota: el que reparte
     * entre lotes es el FIFO, y este metodo solo sabe de si mismo.
     */
    public int consume(int units) {
        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades a consumir deben ser mayores que cero");
        }
        int taken = Math.min(units, this.quantityRemaining);
        this.quantityRemaining -= taken;
        return taken;
    }

    /**
     * Devuelve unidades a este lote al deshacer una venta.
     *
     * Nunca por encima de lo recibido: pasarse significaria que se devolvio mas
     * de lo que este lote llego a entregar, y eso solo puede venir de una
     * asignacion corrupta o de una doble restauracion. Se corta aqui con un
     * error de negocio antes de que el CHECK del esquema lo convierta en un 500.
     */
    public void restore(int units) {
        if (units <= 0) {
            throw new ValidationException(
                    "Las unidades a devolver deben ser mayores que cero");
        }
        if (this.quantityRemaining + units > this.quantityReceived) {
            throw new ValidationException(
                    "No se pueden devolver mas unidades de las que salieron del lote");
        }
        this.quantityRemaining += units;
    }

    /** Cierto cuando el lote lleva un costo de compra real que se puede aplicar. */
    public boolean hasCost() {
        return this.origin == AcquisitionOrigin.PURCHASE && this.unitCost != null;
    }

    /**
     * Gasto total del lote. Se DERIVA y no se persiste: es siempre
     * {@code cantidad x costo unitario}, asi que guardarlo crearia una columna
     * que puede contradecir a las otras dos sin que nada lo impida.
     */
    public BigDecimal totalCost() {
        if (!hasCost()) {
            return null;
        }
        return this.unitCost.multiply(BigDecimal.valueOf(this.quantityReceived));
    }

}
