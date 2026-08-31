package com.cns.spring_house.acquisition.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de proveedores.
 *
 * Las tres magnitudes salen de lotes {@code PURCHASE} reales: un lote de
 * produccion no tiene proveedor y no puede sumar aqui.
 *
 * {@code totalSpend} es {@code cantidad recibida x costo unitario}, es decir lo
 * DESEMBOLSADO, no el valor de lo que queda en bodega: lo que se pago se pago el
 * dia de la compra y no cambia porque la mercaderia se venda despues.
 */
public interface SupplierPerformanceProjection {

    UUID getSupplierId();

    String getSupplierName();

    long getAcquisitionCount();

    long getUnitsReceived();

    BigDecimal getTotalSpend();

    BigDecimal getNationalSpend();

    BigDecimal getImportationSpend();

}
