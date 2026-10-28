package com.cns.spring_house.workresult.entity;

/**
 * Estado COMERCIAL de un trabajo ya hecho, no su avance tecnico.
 *
 * Un {@code WorkResult} solo nace cuando el trabajo existe: al registrarse el
 * {@code JobLog} de un servicio ejecutado o al generar una transformacion su
 * pieza de salida. No hay un estado de "por hacer"; eso vive en la comanda
 * ({@code Fulfillment}) y en el propio JobLog o Transformation, que son la
 * historia tecnica y nunca se reescriben.
 *
 * Que una linea lo tenga asignado es un hecho aparte: el puntero
 * {@code sale_detail.work_result_id}, unico entre todas las lineas.
 */
public enum WorkResultStatus {

    /**
     * Hecho y todavia no facturado. Disponible para facturar cuando ninguna
     * linea lo apunta; anular la venta que lo tenia suelta el puntero y lo
     * deja aqui, sin tocar su historia tecnica.
     */
    PENDING,
    /** Facturado: lo marco la finalizacion de la venta, bajo cerrojo de fila. */
    SOLD;

}
