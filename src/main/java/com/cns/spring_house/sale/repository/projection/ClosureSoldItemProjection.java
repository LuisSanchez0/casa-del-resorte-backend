package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un articulo vendido en un periodo, ya agregado por la base de datos.
 *
 * Se agrupa por {@code productId}, que es la identidad ESTABLE. Agrupar por
 * nombre fusionaria dos productos homonimos distintos —el catalogo real tiene
 * varios "HOJA DE RESORTE 1200"— y presentaria como un solo articulo lo que son
 * dos piezas diferentes.
 *
 * {@code billedAmount} es la suma de {@code sale_detail.sub_total}, el importe
 * HISTORICO de cada linea: incluye el ajuste manual de precio si lo hubo y no se
 * reconstruye multiplicando por el precio de catalogo actual.
 */
public interface ClosureSoldItemProjection {

    UUID getProductId();

    String getItemName();

    /** Categoria de la familia: NORMAL, SPECIAL, CONTAINER o JOB. */
    String getItemType();

    // ── Dimensiones comerciales del producto ─────────────────────
    //
    // Viajan para que el cierre las COPIE en su snapshot, que es lo que hace
    // reproducible el resumen agrupado de un dia ya cerrado. Aqui son el estado
    // ACTUAL del catalogo; en cuanto el cierre se confirma, lo que manda es la
    // copia.

    UUID getFamilyId();

    String getFamilyName();

    /**
     * NULL cuando NO APLICA: la marca se alcanza a traves del modelo, y una
     * familia que no trabaja con modelo no tiene ninguno de los dos.
     */
    UUID getBrandId();

    String getBrandName();

    UUID getModelId();

    String getModelName();

    long getQuantity();

    BigDecimal getBilledAmount();

}
