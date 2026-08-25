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
