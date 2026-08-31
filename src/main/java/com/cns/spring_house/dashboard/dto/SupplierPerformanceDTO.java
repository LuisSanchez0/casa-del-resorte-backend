package com.cns.spring_house.dashboard.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Fila del ranking de proveedores.
 *
 * "Con los que mas se ha trabajado" se define como GASTO REAL, no como numero de
 * pedidos: veinte compras pequeñas no describen al proveedor principal tan bien
 * como lo que de verdad se le ha comprado. Las otras cifras viajan igualmente
 * para que la fila se lea entera.
 *
 * {@code totalSpend} es lo DESEMBOLSADO —cantidad recibida por costo unitario—,
 * no el valor de lo que queda en bodega: lo que se pago se pago el dia de la
 * compra, se haya vendido despues o no.
 */
public record SupplierPerformanceDTO(

        UUID supplierId,
        String supplierName,
        long acquisitionCount,
        long unitsReceived,
        BigDecimal totalSpend,
        BigDecimal nationalSpend,
        BigDecimal importationSpend

) { }
