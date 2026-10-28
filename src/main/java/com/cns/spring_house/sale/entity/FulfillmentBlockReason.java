package com.cns.spring_house.sale.entity;

/**
 * Por que una solicitud esta BLOCKED, y por tanto quien la destraba.
 *
 * Son dos bloqueos distintos aunque compartan estado: confundirlos haria que
 * el vendedor viera "falta material" cuando lo que hace falta es su decision
 * sobre el precio, o al reves.
 */
public enum FulfillmentBlockReason {

    /** No hay pieza de origen compatible. Lo destraba el Operador al retomarla. */
    MATERIAL,
    /**
     * La pieza de origen elegida cuesta mas que el precio autorizado. Lo destraba
     * el Vendedor revisando el precio; el Operador no puede cambiarlo.
     */
    PRICE_REVIEW;

}
