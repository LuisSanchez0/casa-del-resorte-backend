package com.cns.spring_house.sale.dto;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;

/**
 * Una pieza de la que el Operador puede fabricar lo pedido, con lo que va a
 * tener que decidir si la elige.
 */
public record FulfillmentSourceDTO(

    InventoryItemResponseDTO item,
    String familyName,
    String modelName,
    String brandName,
    /** Mismo modelo que la pieza pedida: se ofrece primero. */
    boolean sameModel,
    /** Largo del sobrante que dejaria, o null si no deja. */
    Double remainderLength,
    /**
     * El operador PUEDE registrar los colochos de la pieza resultante: la
     * familia los maneja, la venta no fijo la cantidad y no dijo que la pieza
     * no lleva. No esta obligado; sin indicarlos la pieza queda sin colochos
     * registrados.
     */
    boolean acceptsOutputCurlCount,
    /**
     * Deja sobrante en una familia con colochos. En una comanda el sobrante
     * vuelve al inventario SIN colochos (cero): no se le pregunta a nadie.
     */
    boolean remainderWithoutCurls,
    /**
     * El precio autorizado por el vendedor cubre el costo de esta pieza. Si no
     * lo cubre, elegirla no fabrica: manda la solicitud a revision de precio.
     * Se publica solo el si/no; el importe es del vendedor.
     */
    boolean withinAuthorizedPrice

) {

}
