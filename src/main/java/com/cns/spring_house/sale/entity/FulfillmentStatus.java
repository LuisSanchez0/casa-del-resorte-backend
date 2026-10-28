package com.cns.spring_house.sale.entity;

/**
 * Estado de UNA solicitud de la comanda.
 *
 * <pre>
 * PENDING ──enviar──> QUEUED ──tomar──> IN_PROGRESS ──terminar──> FULFILLED
 *                       │  ^                │
 *                       v  └──tomar──── BLOCKED (sin materia prima)
 *                   CANCELLED (anular la venta antes de cumplirla)
 * </pre>
 *
 * PENDING es una linea que el vendedor todavia puede editar: no tiene ruta ni
 * reserva nada. A partir de QUEUED el taller ya la ve.
 */
public enum FulfillmentStatus {

    PENDING,
    QUEUED,
    IN_PROGRESS,
    BLOCKED,
    FULFILLED,
    CANCELLED;

    /** El taller la tiene en su cola y todavia no la ha terminado. */
    public boolean isOpenInWorkshop() {
        return this == QUEUED || this == IN_PROGRESS || this == BLOCKED;
    }

}
