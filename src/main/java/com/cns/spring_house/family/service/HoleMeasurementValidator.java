package com.cns.spring_house.family.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Regla de dominio de las medidas con orificio.
 *
 * NO se ata a la familia HOJA DE RESORTE ni a ninguna otra por nombre, codigo o
 * id: la unica condicion para que aplique es que la familia declare el conjunto
 * COMPLETO de atributos que la describen —{@code largo}, {@code tieneOrificio},
 * {@code largoIzquierdo} y {@code largoDerecho}— con el tipo que les corresponde
 * ({@code largo}, {@code largoIzquierdo} y {@code largoDerecho} numericos,
 * {@code tieneOrificio} booleano). Cualquier familia que gestione ese conjunto
 * desde el alta de atributos hereda el comportamiento sin tocar codigo; una que
 * declare solo parte de el, o con otro tipo, queda intacta.
 *
 * La regla:
 *
 * <pre>
 * tieneOrificio == false  -> largoIzquierdo y largoDerecho no son obligatorios
 * tieneOrificio == true   -> largo, largoIzquierdo y largoDerecho > 0
 *                            y largoIzquierdo + largoDerecho == largo (igualdad exacta)
 * </pre>
 *
 * La igualdad es OBLIGATORIA: los dos largos de cada lado reparten el largo
 * total completo. La aritmetica es {@link BigDecimal} con {@code compareTo}:
 * comparar en coma flotante haria que {@code 45.1 + 54.9 != 100}.
 *
 * Se valida contra el mapa de atributos EFECTIVO —el que se va a persistir en el
 * alta o edicion de un producto, o el resultante de una transformacion
 * personalizada tras aplicar el PATCH sobre la entrada—, de modo que el largo
 * total contra el que se compara es siempre el que tendra la pieza.
 *
 * Los valores obsoletos de {@code largoIzquierdo} / {@code largoDerecho} que
 * lleguen con {@code tieneOrificio == false} se CONSERVAN tal cual: el resto de
 * la validacion de atributos ya comprueba su tipo y este proyecto no reescribe
 * en silencio lo que el cliente envia. El frontend los limpia al desmarcar el
 * orificio, asi que en el uso normal no perduran.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HoleMeasurementValidator {

    /** Nombres canonicos del conjunto. La deteccion ignora mayusculas. */
    public static final String LENGTH_ATTRIBUTE = "largo";
    public static final String HOLE_ATTRIBUTE = "tieneOrificio";
    public static final String LEFT_LENGTH_ATTRIBUTE = "largoIzquierdo";
    public static final String RIGHT_LENGTH_ATTRIBUTE = "largoDerecho";

    static final String LEFT_REQUIRED_MESSAGE =
            "Debe indicar el largo izquierdo cuando el producto tiene orificio.";
    static final String RIGHT_REQUIRED_MESSAGE =
            "Debe indicar el largo derecho cuando el producto tiene orificio.";
    static final String LENGTH_REQUIRED_MESSAGE =
            "Debe indicar el largo total cuando el producto tiene orificio.";
    static final String LEFT_NOT_POSITIVE_MESSAGE =
            "El largo izquierdo debe ser mayor a 0.";
    static final String RIGHT_NOT_POSITIVE_MESSAGE =
            "El largo derecho debe ser mayor a 0.";
    static final String LENGTH_NOT_POSITIVE_MESSAGE =
            "El largo total debe ser mayor a 0.";
    static final String SUM_NOT_EQUAL_MESSAGE =
            "La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.";

    /**
     * {@code true} cuando la familia declara el conjunto completo con el tipo
     * correcto. Si falta alguno, o alguno esta declarado con otro tipo, la regla
     * no aplica y el producto se valida como cualquier otro.
     */
    public static boolean applies(Family family) {
        return declaresNumber(family, LENGTH_ATTRIBUTE)
                && declaresNumber(family, LEFT_LENGTH_ATTRIBUTE)
                && declaresNumber(family, RIGHT_LENGTH_ATTRIBUTE)
                && declares(family, HOLE_ATTRIBUTE, AttributeDataType.BOOLEAN);
    }

    /**
     * {@code true} para {@code largoIzquierdo} / {@code largoDerecho} cuando la
     * familia declara el conjunto completo: su obligatoriedad la decide esta
     * regla segun {@code tieneOrificio}, no el {@code isRequired} del atributo,
     * de modo que sin orificio no bloqueen el guardado aunque la familia los
     * haya declarado obligatorios.
     */
    public static boolean ownsRequiredness(Family family, String attributeName) {
        return applies(family)
                && attributeName != null
                && (attributeName.equalsIgnoreCase(LEFT_LENGTH_ATTRIBUTE)
                        || attributeName.equalsIgnoreCase(RIGHT_LENGTH_ATTRIBUTE));
    }

    /**
     * Comprueba la regla sobre el mapa de atributos efectivo. No hace nada si la
     * familia no declara el conjunto completo o si {@code tieneOrificio} no es
     * exactamente {@code true}.
     *
     * @throws ValidationException con un mapa {@code campo -> mensaje} y, como
     *         detalle, el mensaje de negocio mas relevante.
     */
    public static void validate(Family family, Map<String, Object> attributes) {
        if (!applies(family) || attributes == null) {
            return;
        }
        if (!Boolean.TRUE.equals(readBoolean(attributes, HOLE_ATTRIBUTE))) {
            return;
        }

        Map<String, String> errors = new LinkedHashMap<>();

        String leftKey = canonicalKey(family, LEFT_LENGTH_ATTRIBUTE);
        String rightKey = canonicalKey(family, RIGHT_LENGTH_ATTRIBUTE);
        String lengthKey = canonicalKey(family, LENGTH_ATTRIBUTE);

        BigDecimal left = readDecimal(attributes, LEFT_LENGTH_ATTRIBUTE);
        BigDecimal right = readDecimal(attributes, RIGHT_LENGTH_ATTRIBUTE);
        BigDecimal total = readDecimal(attributes, LENGTH_ATTRIBUTE);

        // Con orificio, cada medida debe ser estrictamente mayor a 0 y los dos
        // largos de cada lado deben repartir el largo total COMPLETO: su suma es
        // exactamente igual al total, no solo menor o igual.
        if (left == null) {
            errors.put(leftKey, LEFT_REQUIRED_MESSAGE);
        } else if (left.signum() <= 0) {
            errors.put(leftKey, LEFT_NOT_POSITIVE_MESSAGE);
        }
        if (right == null) {
            errors.put(rightKey, RIGHT_REQUIRED_MESSAGE);
        } else if (right.signum() <= 0) {
            errors.put(rightKey, RIGHT_NOT_POSITIVE_MESSAGE);
        }
        if (total == null) {
            errors.put(lengthKey, LENGTH_REQUIRED_MESSAGE);
        } else if (total.signum() <= 0) {
            errors.put(lengthKey, LENGTH_NOT_POSITIVE_MESSAGE);
        }

        boolean sumNotEqual = left != null && right != null && total != null
                && left.signum() > 0 && right.signum() > 0 && total.signum() > 0
                && left.add(right).compareTo(total) != 0;
        if (sumNotEqual) {
            errors.put(rightKey, SUM_NOT_EQUAL_MESSAGE);
        }

        if (errors.isEmpty()) {
            return;
        }

        String detail = sumNotEqual ? SUM_NOT_EQUAL_MESSAGE : errors.values().iterator().next();
        throw new ValidationException(detail, errors);
    }

    // ── Deteccion de atributos ─────────────────────────────────────────

    private static boolean declaresNumber(Family family, String name) {
        return declares(family, name, AttributeDataType.NUMBER);
    }

    private static boolean declares(Family family, String name, AttributeDataType type) {
        return declaredAttribute(family, name)
                .map(attribute -> attribute.getDataType() == type)
                .orElse(false);
    }

    private static Optional<Attribute> declaredAttribute(Family family, String name) {
        List<Attribute> declared = family == null ? null : family.getAttributes();
        if (declared == null || name == null) {
            return Optional.empty();
        }
        return declared.stream()
                .filter(attribute -> attribute.getName() != null
                        && attribute.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /** Nombre real con el que la familia escribe el atributo, para el mapa de errores. */
    private static String canonicalKey(Family family, String name) {
        return declaredAttribute(family, name)
                .map(Attribute::getName)
                .orElse(name);
    }

    // ── Lectura de valores (el JSONB no distingue el caso de la clave) ──

    private static Object readRaw(Map<String, Object> attributes, String name) {
        if (attributes.containsKey(name)) {
            return attributes.get(name);
        }
        return attributes.entrySet()
                .stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Boolean readBoolean(Map<String, Object> attributes, String name) {
        return readRaw(attributes, name) instanceof Boolean value ? value : null;
    }

    /**
     * El valor como {@link BigDecimal} exacto, o {@code null} si no es un numero.
     * Los {@link Number} se leen por su representacion en texto para no arrastrar
     * el error de {@code doubleValue()} ({@code new BigDecimal(0.1)} != 0.1).
     */
    private static BigDecimal readDecimal(Map<String, Object> attributes, String name) {
        Object value = readRaw(attributes, name);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

}
