package com.cns.spring_house.inventory.entity;

/**
 * Donde esta una unidad fisica respecto al stock libre.
 *
 * Es un estado de INVENTARIO, no financiero. Ningun reporte, cierre de caja ni
 * estadistica de ventas lo lee: lo vendido y lo cobrado salen de la venta
 * ({@code Sale.status = COMPLETED}, {@code paymentStatus = PAID}).
 */
public enum InventoryItemStatus {

    /** En stock y libre: la puede tomar cualquier venta. */
    AVAILABLE,
    /**
     * Fuera del stock libre porque una linea de venta la tiene: apartada en una
     * venta abierta (reservada al confirmar la linea o al enviar la comanda) o
     * vendida en una emitida. Quitar la linea o anular la venta la devuelven a
     * {@link #AVAILABLE}; emitirla no la cambia. Que se cobro o no lo dice la
     * venta, nunca esta unidad.
     */
    SOLD,
    /** Usada como origen de una transformacion: ya no existe como tal. */
    CONSUMED,
    /** Componente metido en una maleta, o maleta a medio desarmar: no se vende como unidad. */
    UNAVAILABLE;

}
