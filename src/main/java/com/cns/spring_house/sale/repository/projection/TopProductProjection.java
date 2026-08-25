package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking "productos mas vendidos hoy", agrupada por
 * {@code Product.id}, NUNCA por {@code invoiceDescription}: el mismo producto
 * puede venderse con distintos nombres de factura y seguir siendo la misma
 * pieza para efectos de inventario y catalogo.
 */
public interface TopProductProjection {

    UUID getProductId();

    String getProductName();

    long getQuantity();

    BigDecimal getAmount();

}
