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
 *
 * COMO se cobro NO vive aqui, y es deliberado: el cierre ya guarda el desglose
 * de cobros en {@code cash_closure_payment_method}, que es su unica fuente. Una
 * segunda copia por articulo seria una segunda verdad sobre el mismo dinero, y
 * dos verdades acaban contradiciendose.
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


    // ── Dimensiones comerciales, copiadas al cerrar ──────────────
    //
    // Son la respuesta a "que grupos de mercaderia se vendieron": el resumen
    // agrupado suma por familia + marca + modelo, y lo hace sobre ESTAS
    // columnas y no sobre el catalogo vivo. Resolverlas al descargar el PDF
    // dejaria que renombrar una marca reescribiera un documento ya firmado.
    //
    // De cada dimension se copia el id Y el nombre: el id agrupa —dos familias
    // homonimas son dos grupos— y el nombre imprime, de modo que el historico
    // siga siendo legible aunque el catalogo cambie despues.
    //
    // Sin FK a `family`, `brand` ni `model`, por lo mismo que `productId`: es
    // historia copiada, y una FK impediria borrar del catalogo una marca que un
    // cierre antiguo menciona.

    /**
     * Familia al cerrar.
     *
     * Su CALIDAD depende de cuando se confirmo el cierre, y conviene no
     * confundir las dos:
     *
     * <pre>
     * confirmado DESPUES de V37  valor exacto, copiado al confirmar
     * confirmado ANTES de V37    reconstruido por la migracion, mejor esfuerzo
     * </pre>
     *
     * En el segundo caso V37 copio lo que el catalogo decia al migrar: si el
     * producto ya se habia renombrado o movido de familia antes, la etiqueta
     * original de aquel dia es INRECUPERABLE, porque estas columnas no existian
     * cuando ese cierre se confirmo.
     *
     * NULL solo en cierres anteriores a V37 cuyo producto ya no esta en el
     * catalogo. El reporte los agrupa como SIN CLASIFICAR sin inventarles una
     * familia, y su importe sigue contando en el total.
     */
    private UUID familyId;

    @Column(length = ColumnLimits.FAMILY_NAME)
    private String familyName;

    /**
     * Marca al cerrar. NULL cuando NO APLICA, que no es lo mismo que faltar: la
     * marca se alcanza a traves del modelo ({@code Product.model.brand}), y una
     * familia con {@code requiresModel} en false no tiene ninguno de los dos.
     */
    private UUID brandId;

    @Column(length = ColumnLimits.BRAND_NAME)
    private String brandName;

    /** Modelo al cerrar. NULL cuando la familia no trabaja con modelo. */
    private UUID modelId;

    @Column(length = ColumnLimits.MODEL_NAME)
    private String modelName;

}
