package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Un GRUPO COMERCIAL vendido en el periodo: familia, mas marca y modelo cuando
 * aplican, con sus unidades y su importe.
 *
 * Es otra PRESENTACION de los mismos articulos que ya publica el cierre, no una
 * cifra nueva: {@code amount} sale de sumar el mismo `billedAmount` historico
 * de las filas del snapshot, nunca de multiplicar por el precio de catalogo de
 * hoy.
 *
 * <h2>Que parte y que no parte un grupo</h2>
 *
 * <pre>
 * PARTE     familia distinta, marca distinta, modelo distinto
 * NO PARTE  medidas (largo, orificios, colochos...), `product.id`, sku,
 *           nombre del producto, categoria de la familia
 * </pre>
 *
 * Tres hojas de resorte Toyota Hilux de 80, 90 y 100 son UNA fila. Son la misma
 * mercaderia para quien compra y para quien repone; que midan distinto es un
 * detalle de la pieza, no del grupo.
 *
 * Los ids son la identidad por la que se agrupo y los nombres son solo para
 * imprimir: dos familias homonimas son dos grupos, no uno.
 *
 * {@code brandId}, {@code brandName}, {@code modelId} y {@code modelName} son
 * nulos cuando la dimension NO APLICA. El PDF los pinta como un guion, pero el
 * guion es tinta: lo que agrupa es el nulo.
 */
public record CashClosureProductGroupDTO(

    /** Nulo solo en cierres anteriores a V37 cuyo producto ya no existe. */
    UUID familyId,
    String familyName,
    UUID brandId,
    String brandName,
    UUID modelId,
    String modelName,
    long quantity,
    BigDecimal amount

) { }
