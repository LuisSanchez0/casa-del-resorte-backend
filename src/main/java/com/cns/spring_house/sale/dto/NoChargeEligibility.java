package com.cns.spring_house.sale.dto;

/**
 * Si una linea de esta pieza podria pedirse "sin cargo" (Q0.00), y si no, por
 * que. Es la MISMA regla que aplica el servidor al agregar y al corregir el
 * precio ({@code SaleDetailHandler.requireRecordedZeroCost}); la guia solo la
 * adelanta para no ofrecer lo que se rechazaria. Al agregar se vuelve a decidir
 * con las unidades que realmente se toman.
 */
public enum NoChargeEligibility {

    /** Precio de catalogo, ningun minimo y las unidades libres con costo registrado en cero. */
    ALLOWED,
    /** Sin precio de catalogo: el cero es "sin precio", nunca "sin cargo". */
    NO_CATALOG,
    /** Tiene costo: no se vende por debajo de el. */
    COST,
    /** Costo desconocido (lote de produccion, servicio sin costo en el modelo): no es cero. */
    COST_UNKNOWN,
    /** Se pide al taller: la pieza y su costo se conocen al cumplirla. */
    REQUEST

}
