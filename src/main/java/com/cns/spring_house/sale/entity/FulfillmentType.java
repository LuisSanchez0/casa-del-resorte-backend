package com.cns.spring_house.sale.entity;

/**
 * Como se cumple una solicitud de la comanda, y por tanto QUIEN la cumple.
 *
 * El area responsable no se guarda aparte: se deriva de aqui, de modo que una
 * fila nunca puede decir "Control" y "transformacion" a la vez.
 */
public enum FulfillmentType {

    /** La pieza pedida ya existe: Control entrega la unidad fisica exacta. */
    STOCK_RELEASE(FulfillmentArea.CONTROL),
    /** La pieza pedida no hay en stock: el Operador la fabrica. */
    TRANSFORMATION(FulfillmentArea.OPERATOR),
    /** Un servicio del catalogo que el Operador ejecuta. */
    SERVICE(FulfillmentArea.OPERATOR);

    private final FulfillmentArea area;

    FulfillmentType(FulfillmentArea area) {
        this.area = area;
    }

    public FulfillmentArea area() {
        return area;
    }

}
