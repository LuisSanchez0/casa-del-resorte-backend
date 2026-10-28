package com.cns.spring_house.transformation.handler;

/**
 * Quien decide los colochos en una transformacion SPECIAL: los de la pieza
 * principal (dentro de la misma familia) y los del sobrante.
 *
 * <ul>
 * <li>{@link #REQUIRED}: la transformacion manual del Operador. El operador
 * describe la pieza que quiere obtener, asi que indica siempre sus colochos y
 * los del sobrante (`0` = sin colochos). Es la regla de siempre.</li>
 * <li>{@link #AS_REQUESTED}: la pieza pedida en una venta (comanda). Manda lo
 * que pidio el vendedor: si fijo los colochos, son esos; si dijo que no lleva,
 * cero; si no fijo la cantidad, la pieza resultante no la lleva registrada,
 * salvo que el operador la indique. Una ausencia NO se convierte en `0` ni se
 * hereda de la pieza de origen, que es un dato que nadie decidio para esta
 * pieza. El SOBRANTE, que vuelve al inventario y no es de la venta, queda sin
 * colochos (cero) sin que nadie lo indique.</li>
 * </ul>
 *
 * Solo el servidor elige el modo: la comanda llama a la transformacion con
 * {@link #AS_REQUESTED}; el endpoint de transformaciones, siempre con
 * {@link #REQUIRED}. Ningun cliente puede pedir el modo de la comanda.
 */
public enum CurlCountRequirement {

    REQUIRED,
    AS_REQUESTED

}
