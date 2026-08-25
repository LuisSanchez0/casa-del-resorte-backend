package com.cns.spring_house.sale.entity;

/**
 * {@code CANCELLED} y {@code REVERSED} son eventos historicamente distintos
 * aunque ambos sean terminales: una venta cancelada nunca llego a completarse,
 * mientras que una revertida se emitio y despues se deshizo. Confundirlas en un
 * unico estado le quitaria al historial la posibilidad de distinguir "esto no
 * llego a venderse" de "esto se vendio y se corrigio".
 */
public enum SaleStatus {

    PENDING,
    COMPLETED,
    CANCELLED,
    REVERSED;

}
