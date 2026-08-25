package com.cns.spring_house.shared.util;

import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Construccion de patrones LIKE a partir de texto escrito por una persona.
 *
 * Un parametro enlazado impide la inyeccion SQL, pero NO le quita a `%` y `_`
 * su significado de comodin. Sin escaparlos, buscar "%" devolvia TODAS las
 * facturas del taller y "_" casaba con cualquier caracter: quien busca una
 * factura escribe texto literal, no sintaxis de LIKE.
 *
 * El caracter de escape se escapa PRIMERO. Hacerlo al final volveria a escapar
 * las barras que acaban de introducir los dos reemplazos anteriores, y "50%"
 * terminaria buscando algo que nadie escribio.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LikePatterns {

    /**
     * Caracter de escape de los patrones. Coincide con el que PostgreSQL usa
     * por defecto, pero las consultas lo declaran igualmente con `ESCAPE` para
     * no depender de un valor por defecto del motor.
     */
    public static final String ESCAPE = "\\";

    /**
     * El mismo caracter para la Criteria API, que declara el escape con un
     * {@code char} en vez de con el texto `ESCAPE '\\'` del JPQL. Se deriva de
     * {@link #ESCAPE} para que no puedan separarse.
     */
    public static final char ESCAPE_CHAR = ESCAPE.charAt(0);

    /**
     * Texto literal en cualquier posicion: `%texto%`, en minusculas y sin
     * comodines del usuario. Pensado para columnas comparadas con `LOWER(...)`.
     */
    public static String containsIgnoreCase(String value) {
        return "%" + escape(value.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private static String escape(String value) {
        return value
                .replace(ESCAPE, ESCAPE + ESCAPE)
                .replace("%", ESCAPE + "%")
                .replace("_", ESCAPE + "_");
    }

}
