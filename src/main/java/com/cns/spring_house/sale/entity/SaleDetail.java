package com.cns.spring_house.sale.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.workresult.entity.WorkResult;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@EqualsAndHashCode(of = "id")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SaleDetail {
    
    @UuidGenerator
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_id")
    private Sale sale;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private InventoryItem item;
    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_result_id")
    private WorkResult workResult;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal subTotal;
    /**
     * Lo que COSTO la mercaderia de esta linea, capturado con la politica FIFO
     * en el mismo instante en que el stock salio del inventario (al agregar la
     * linea, no al emitir el documento).
     *
     * Es historia contable inmutable: no se recalcula nunca contra
     * {@code Inventory.cost} —que es el costo de referencia ACTUAL y cada compra
     * lo pisa— ni contra el precio de catalogo de hoy. Una compra posterior mas
     * cara no reescribe lo que esta venta costo.
     *
     * NULL NO ES CERO, y la diferencia es el motivo de que la columna sea
     * nullable:
     *
     * <ul>
     * <li>Un importe significa que las unidades salieron de lotes comprados y su
     * costo se conoce con exactitud.</li>
     * <li>NULL significa que esta linea no tiene costo de adquisicion: un
     * servicio, o mercaderia que entro por produccion (salida de una
     * transformacion, maleta armada con componentes propios).</li>
     * </ul>
     *
     * Rellenar el segundo caso con 0 convertiria el precio entero en ganancia,
     * que es exactamente la cifra inventada que este modelo existe para evitar.
     * El panel distingue los dos casos en lugar de sumarlos.
     *
     * Lo escribe SIEMPRE el servidor. El cliente no lo envia ni puede enviarlo:
     * no aparece en ningun DTO de peticion.
     *
     * Que lotes lo compusieron vive en {@code SaleDetailCostAllocation}, que es
     * lo que permite devolver el stock al lote exacto al deshacer la venta.
     */
    private BigDecimal costTotal;
    /**
     * Descripcion alterna SOLO para el documento fiscal. El catalogo, el
     * inventario y el historial tecnico siguen viendo {@code product.name}: aqui
     * se guarda un snapshot de lo que debe imprimirse en la factura, que suele ser
     * mas generico que el nombre tecnico del producto.
     */
    private String invoiceDescription;

    /**
     * Texto que debe aparecer en la factura. La descripcion alterna solo se usa si
     * realmente se informo; en cualquier otro caso manda el nombre del producto.
     */
    public String resolveInvoiceDescription() {
        return invoiceDescription == null || invoiceDescription.isBlank()
                ? product.getName()
                : invoiceDescription;
    }

}
