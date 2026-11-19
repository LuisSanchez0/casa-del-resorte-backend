package com.cns.spring_house.shared.exception;

/**
 * El precio que el vendedor confirmo se decidio sobre un precio de catalogo
 * que ya no es el vigente: otra caja o Control lo cambiaron mientras tanto.
 *
 * No es un error de validacion (400): la peticion estaba bien formada y era
 * valida contra lo que el vendedor vio. Es un conflicto de estado (409), y lo
 * que corresponde es volver a leer el precio y reconfirmarlo, no corregir un
 * campo.
 */
public class PriceConflictException extends RuntimeException {

    public PriceConflictException(String message) {
        super(message);
    }

}
