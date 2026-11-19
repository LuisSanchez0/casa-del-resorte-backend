package com.cns.spring_house.sale.dto;

/**
 * De donde sale una referencia de precio. Lo decide SIEMPRE el servidor; el
 * cliente nunca lo envia. Ver {@code SaleLineGuidanceServiceImpl} para el
 * orden de prioridad.
 *
 * <ul>
 * <li>LAST_SALE: el ultimo precio cobrado por ESTA misma pieza en una venta
 * completada de este taller. Puede proponerse.</li>
 * <li>EQUIVALENT_PIECE: el precio de catalogo, en este taller, de otro
 * registro con la misma familia, modelo y EXACTAMENTE las mismas
 * caracteristicas. Puede proponerse, rotulado como referencia.</li>
 * <li>SIMILAR_PIECE: el precio de catalogo de una pieza con UNA medida
 * distinta. Es contexto con la diferencia a la vista; nunca se propone
 * sola.</li>
 * </ul>
 */
public enum PriceReferenceSource {
    LAST_SALE,
    EQUIVALENT_PIECE,
    SIMILAR_PIECE
}
