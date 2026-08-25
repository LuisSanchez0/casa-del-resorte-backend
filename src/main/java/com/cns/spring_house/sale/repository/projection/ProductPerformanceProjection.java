package com.cns.spring_house.sale.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de productos del dashboard administrativo, agrupada por
 * {@code Product.id} y no por {@code invoiceDescription}, igual que
 * {@link TopProductProjection}.
 *
 * Las dos magnitudes son HISTORICAS y exactas: salen de
 * {@code sale_detail.quantity} y {@code sale_detail.sub_total}, snapshots que se
 * escriben al facturar y que nada vuelve a modificar. El subtotal ya refleja el
 * ajuste manual de precio si lo hubo, asi que un descuento concedido en su dia
 * sigue viendose aqui.
 *
 * {@code generatedAmount} es MONTO GENERADO por esas ventas, NO margen: no se
 * resta ningun costo y la consulta no toca {@code inventory}. La pregunta que
 * responde el ranking es "cuanto vendio este producto", no "cuanto dejo".
 */
public interface ProductPerformanceProjection {

    UUID getProductId();

    String getProductName();

    long getQuantity();

    BigDecimal getGeneratedAmount();

}
