package com.cns.spring_house.sale.dto;

import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Normalizacion y presentacion del nombre escrito en la venta: el alta, el
 * guardado para despues y la finalizacion guardan exactamente la misma forma,
 * y los documentos del servidor presentan igual una venta sin nombre.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SaleNames {

    /**
     * Como se presenta una venta a Consumidor Final sin nombre. El mismo texto
     * que usa el frontend (`CONSUMIDOR_FINAL_NAME`).
     */
    public static final String CONSUMIDOR_FINAL = "Consumidor Final";

    /** trim + mayusculas; en blanco es null, nunca una cadena vacia. */
    static String normalize(String name) {
        return name == null || name.isBlank()
                ? null
                : name.trim().toUpperCase(Locale.ROOT);
    }

}
