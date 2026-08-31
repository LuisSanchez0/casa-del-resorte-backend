package com.cns.spring_house.cashclosure.entity;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.shared.audit.Auditable;
import com.cns.spring_house.shared.constants.ColumnLimits;

import jakarta.persistence.Column;
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
 * Un articulo vendido dentro de un cierre, ya agregado: una fila por producto.
 *
 * Es una COPIA, no una referencia. El nombre y la categoria se guardan tal y
 * como estaban al cerrar, y no hay FK a `product`: renombrar un producto el mes
 * que viene no puede cambiar lo que dice un documento ya firmado.
 *
 * {@code productId} se conserva igualmente porque es la identidad ESTABLE por
 * la que se agrupo. Agrupar por nombre fusionaria dos productos homonimos
 * distintos —el catalogo real los tiene—, y guardar solo el nombre haria
 * imposible saber despues cual era cual.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(of = "id", callSuper = false)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CashClosureItem extends Auditable {

    @UuidGenerator
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_closure_id")
    private CashClosure cashClosure;

    /** Identidad estable por la que se agrupo. Sin FK: es historia copiada. */
    private UUID productId;

    @Column(length = ColumnLimits.PRODUCT_NAME)
    private String itemName;

    /** Categoria de la familia al cerrar: NORMAL, SPECIAL, CONTAINER o JOB. */
    @Enumerated(EnumType.STRING)
    private FamilyCategory itemType;

    private long quantity;

    /** Suma de `sale_detail.sub_total` de ese producto en el periodo. */
    private BigDecimal billedAmount;

}
