package com.cns.spring_house.sale.entity;

/**
 * Cola del taller a la que va una solicitud.
 *
 * El nombre es del AREA, no del rol que la opera: los dos valores forman parte
 * del contrato (`area` en las respuestas, la ruta {@code /v1/fulfillments/control})
 * y se conservan. Hoy ambas colas las opera el rol OPERATOR.
 */
public enum FulfillmentArea {

    /** Entrega de stock: "Pedidos por liberar". La opera el Operador (antes, Control). */
    CONTROL,
    /** Fabricacion y servicios: "Pedidos del taller". */
    OPERATOR;

}
