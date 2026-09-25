package com.cns.spring_house.cashclosure.service;

import java.util.UUID;

/**
 * Las dimensiones COMERCIALES de un producto vendido: familia, marca y modelo.
 *
 * Es lo que el resumen agrupado necesita y el articulo suelto no responde. El
 * taller no pregunta "cuantos HOJA DE RESORTE 1200 vendi": pregunta cuantas
 * hojas Toyota Hilux, sin importar que unas midieran 80 y otras 100.
 *
 * Viaja SEPARADA de {@code ClosureSoldItemDTO} a proposito. El articulo es lo
 * que la pantalla y el PDF de cierre ya publican y su contrato no cambia; esto
 * es material de snapshot, se copia al cerrar y solo lo consume el reporte
 * agrupado.
 *
 * <h2>Cuando aplica cada dimension</h2>
 *
 * <pre>
 * familia  SIEMPRE. `product.family_id` es NOT NULL.
 * marca    solo si el producto tiene modelo.
 * modelo   solo si la familia lo exige (`family.requiresModel`).
 * </pre>
 *
 * Marca y modelo estan atados: la marca se alcanza a traves del modelo
 * ({@code Product.model.brand}, con `brand_id` NOT NULL), asi que o vienen los
 * dos o no viene ninguno. Un NULL aqui significa NO APLICA, nunca un dato que
 * falte.
 */
public record ClosureProductDimensions(

    UUID familyId,
    String familyName,
    /** NULL cuando la familia no trabaja con modelo. */
    UUID brandId,
    String brandName,
    UUID modelId,
    String modelName

) { }
