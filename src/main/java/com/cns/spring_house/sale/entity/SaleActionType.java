package com.cns.spring_house.sale.entity;

/**
 * Distingue el evento auditado que produjo un {@link SaleAction}: anular una
 * venta que nunca llego a completarse, o revertir una que ya se habia
 * completado. Comparten el mismo mecanismo de auditoria (motivo, autor,
 * fecha) pero son hechos de negocio distintos.
 */
public enum SaleActionType {

    CANCEL_PENDING,
    REVERSE_COMPLETED;

}
