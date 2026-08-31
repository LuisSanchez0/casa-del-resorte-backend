package com.cns.spring_house.cashclosure.service;

import java.util.Locale;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * El codigo corto con el que una persona reconoce un documento.
 *
 * Este modelo no tiene numero de factura: la venta se identifica por su UUID. Y
 * un UUID entero no es algo que nadie lea en un papel ni teclee en un buscador,
 * asi que la interfaz muestra sus primeros caracteres.
 *
 * No es una convencion nueva: el buscador de facturas ya compara contra
 * {@code LOWER(CAST(sale.id AS String)) LIKE} precisamente porque la secretaria
 * teclea los primeros caracteres del identificador impreso. Esto le da nombre a
 * lo que ya se hacia.
 *
 * Ocho caracteres del primer bloque del UUID: suficientes para distinguir los
 * documentos de un taller a simple vista, y lo bastante cortos para caber en
 * una columna. El UUID completo sigue viajando aparte cuando hace falta abrir el
 * documento.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DocumentCodes {

    public static final int LENGTH = 8;

    public static String of(UUID id) {
        if (id == null) {
            return null;
        }
        return id.toString()
                .substring(0, LENGTH)
                .toUpperCase(Locale.ROOT);
    }

}
