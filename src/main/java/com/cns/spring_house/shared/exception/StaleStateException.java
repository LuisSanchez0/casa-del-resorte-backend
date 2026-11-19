package com.cns.spring_house.shared.exception;

/**
 * La peticion se decidio sobre un estado que otra sesion ya cambio: por
 * ejemplo, un dialogo abierto mostraba un mecanico asignado que ya no es el
 * vigente.
 *
 * Igual que {@link PriceConflictException}, no es un error de validacion
 * (400): la peticion estaba bien formada y era valida contra lo que el usuario
 * vio. Es un conflicto de estado (409). Nada se guardo; el cliente vuelve a
 * leer el registro y el usuario decide otra vez.
 */
public class StaleStateException extends RuntimeException {

    public StaleStateException(String message) {
        super(message);
    }

}
