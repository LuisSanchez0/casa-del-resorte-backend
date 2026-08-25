package com.cns.spring_house.sale.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de productos mas vendidos hoy. Identifica el producto
 * TECNICO (`Product.id`/`Product.name`), no el nombre de factura de una venta
 * concreta.
 */
public record TopProductDTO(

    UUID productId,
    String productName,
    long quantity,
    BigDecimal amount

) {

}
