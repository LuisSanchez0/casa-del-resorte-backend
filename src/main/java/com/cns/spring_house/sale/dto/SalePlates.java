package com.cns.spring_house.sale.dto;

import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Normalizacion de la placa escrita en la venta: el alta, la linea de servicio
 * y el envio de la comanda guardan exactamente la misma forma.
 *
 * Es la de VehicleMovement (trim + mayusculas con Locale.ROOT), para que la
 * placa de una venta y la de un ingreso sean comparables. No se aplica ningun
 * patron de placa: la columna solo limita el largo, y uno mas estricto
 * rechazaria placas que el taller atiende.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SalePlates {

    /** trim + mayusculas; en blanco es null, nunca una cadena vacia. */
    static String normalize(String plate) {
        return plate == null || plate.isBlank()
                ? null
                : plate.trim().toUpperCase(Locale.ROOT);
    }

}
