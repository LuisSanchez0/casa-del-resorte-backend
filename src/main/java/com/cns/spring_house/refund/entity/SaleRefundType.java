package com.cns.spring_house.refund.entity;

/**
 * Clase de salida de dinero.
 *
 * Hoy solo hay una. Existe como enum y no como constante implicita porque esta
 * tabla es el UNICO sitio del dominio donde el dinero sale, y una nota de
 * credito o un ajuste de caja futuros serian otra clase del mismo hecho: mejor
 * discriminarlos por columna que migrar la tabla cuando lleguen.
 */
public enum SaleRefundType {

    /** Dinero devuelto al cliente por una venta revertida. */
    REFUND

}
