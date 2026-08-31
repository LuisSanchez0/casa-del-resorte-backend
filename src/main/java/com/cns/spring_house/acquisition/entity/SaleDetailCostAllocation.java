package com.cns.spring_house.acquisition.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.sale.entity.SaleDetail;
import com.cns.spring_house.shared.audit.Auditable;

import jakarta.persistence.Entity;
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
 * Cuanto consumio UNA linea de venta de UN lote concreto.
 *
 * Existe porque una linea puede repartirse entre varios lotes —4 unidades = 2
 * del lote de Q90 y 2 del de Q110— y porque en este dominio el stock se devuelve
 * de verdad: quitar una linea, anular un borrador y revertir una venta ya
 * emitida pasan los tres por el mismo {@code restore} de los handlers.
 *
 * {@code SaleDetail.costTotal} por si solo no permite deshacer nada: dice cuanto
 * costo la linea, pero no de que lotes salio. Sin esta tabla, devolver stock
 * obligaria a adivinar a que lotes regresa —al mas nuevo, al mas viejo—, y
 * cualquier eleccion repondria en el lote equivocado y corrompperia el costo de
 * la siguiente venta que consumiera de ahi.
 *
 * {@code unitCostSnapshot} se COPIA y no se lee del lote por join. Es historia
 * contable: aunque el lote se agote, aunque el proveedor se renombre, aunque
 * manana entre mercaderia mas cara, lo que esta linea costo no se mueve.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SaleDetailCostAllocation extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_detail_id")
    private SaleDetail saleDetail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acquisition_id")
    private InventoryAcquisition acquisition;

    private Integer quantity;

    private BigDecimal unitCostSnapshot;

    private BigDecimal subtotalCost;

}
