package com.cns.spring_house.shared.exception;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.validation.FieldError;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

public class ExceptionUtils {

    private ExceptionUtils() {

    }

    public static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    public static boolean isCausedBy(Throwable throwable, Class<? extends Throwable> exceptionType) {
        if (throwable == null || exceptionType == null) {
            return false;
        }
        Throwable current = throwable;
        while (current != null) {
            if (exceptionType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static Map<String, String> extractFieldErrors(List<FieldError> errors) {
        return errors.stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        FieldError::getDefaultMessage));
    }

    /** Mensaje unico para un campo entero que llego con decimales. */
    private static final String NOT_AN_INTEGER = "El valor debe ser un numero entero";

    /**
     * Campos que fallaron por recibir un decimal donde se espera un entero.
     *
     * La politica de coercion (ver StrictIntegerCoercionConfig) hace que Jackson
     * aborte en lugar de truncar, y el fallo llega envuelto en una excepcion de
     * lectura del cuerpo. Aqui se traduce al mismo mapa `errors` que usa el resto
     * de la API, tomando el nombre del campo de la ruta que reporta Jackson.
     *
     * Nunca se devuelve el texto del parser: describe clases y posiciones
     * internas que no significan nada para el cliente.
     */
    public static Map<String, String> extractNonIntegerFields(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof MismatchedInputException mismatch
                    && isIntegerTarget(mismatch.getTargetType())) {
                String field = lastPathReference(mismatch);
                return field == null
                        ? Map.of()
                        : Map.of(field, NOT_AN_INTEGER);
            }
            current = current.getCause();
        }

        return Map.of();
    }

    private static boolean isIntegerTarget(Class<?> targetType) {
        return targetType == Integer.class || targetType == int.class
                || targetType == Short.class || targetType == short.class
                || targetType == Long.class || targetType == long.class
                || targetType == Byte.class || targetType == byte.class
                || targetType == BigInteger.class;
    }

    /** Ultimo tramo con nombre de la ruta, que es el campo que fallo. */
    private static String lastPathReference(MismatchedInputException mismatch) {
        List<JacksonException.Reference> path = mismatch.getPath();

        for (int index = path.size() - 1; index >= 0; index--) {
            String name = path.get(index).getPropertyName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }

        return null;
    }

}
