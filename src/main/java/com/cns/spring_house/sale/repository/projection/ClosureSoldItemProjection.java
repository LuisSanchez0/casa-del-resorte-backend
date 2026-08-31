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

    long getQuantity();

    BigDecimal getBilledAmount();

}
