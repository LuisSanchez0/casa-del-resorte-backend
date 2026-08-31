package com.cns.spring_house.cashclosure.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * El dinero que SALIO en el periodo.
 *
 * Es la contraparte de `CashClosureCollectionsDTO` y se mantiene separada a
 * proposito: mezclarlas daria un unico numero que no responde ninguna de las
 * dos preguntas del taller —cuanto entro y cuanto se devolvio— y volveria
 * imposible cuadrar la caja contra los cobros.
 *
 * `totalRefunded` es la suma exacta de `methods`, igual que el total cobrado lo
 * es de sus formas de pago.
 */
public record CashClosureRefundsDTO(

    /** Una fila por forma de devolucion, incluidas las que quedaron en cero. */
    List<ClosureRefundMethodDTO> methods,
    long refundCount,
    BigDecimal totalRefunded,
    List<ClosureRefundDTO> refunds

) { }
