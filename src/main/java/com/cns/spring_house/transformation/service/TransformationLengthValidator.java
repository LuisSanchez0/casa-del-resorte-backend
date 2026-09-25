package com.cns.spring_house.transformation.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.service.HoleMeasurementValidator;
import com.cns.spring_house.shared.exception.ValidationException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Regla de dominio del largo de una transformacion: una pieza puede conservar o
 * perder largo, nunca ganarlo.
 *
 * <pre>
 * largoResultante &lt;= largoOriginalDeLaEntrada
 * </pre>
 *
 * Transformar es modificar una pieza fisica que ya existe: se corta, se
 * rectifica, se le hace un orificio. De un material de 200 no sale uno de 250,
 * asi que un resultado mas largo que la entrada no describe una transformacion
 * sino una pieza distinta. La igualdad SI se permite —cambiar los colochos o
 * abrir un orificio no altera el largo—.
 *
 * Como {@link HoleMeasurementValidator}, no se ata a ninguna familia por
 * nombre, codigo ni id: la unica condicion para que aplique es que la familia
 * declare {@code largo} como atributo numerico. Una familia que no lo declare
 * queda intacta.
 *
 * <h2>Que se compara</h2>
 *
 * <ul>
 *   <li><b>original</b>: el {@code largo} guardado en el Product de la unidad
 *       que se esta transformando, ANTES de aplicar nada. Nunca el del producto
 *       de referencia ni el de un catalogo: la referencia describe lo que se
 *       quiere, no lo que hay.</li>
 *   <li><b>resultante</b>: el {@code largo} del mapa de atributos EFECTIVO, ya
 *       resuelto el modo (personalizado o copia de referencia) y ya aplicado
 *       {@code outputCurlCount}.</li>
 * </ul>
 *
 * Comparar el efectivo y no lo que pidio el cliente es lo que cierra el modo
 * copia: copiar el {@code largo} de una referencia de 250 sobre una entrada de
 * 200 produce un efectivo de 250 y se rechaza igual que si se hubiera escrito a
 * mano. No se trunca a 200 en silencio: el operador pidio una pieza que no se
 * puede fabricar y tiene que enterarse.
 *
 * <h2>Entrada estructuralmente inconsistente</h2>
 *
 * Si la familia declara {@code largo} numerico pero la entrada no lo tiene, o
 * lo tiene con un valor que no es un numero, la regla NO se salta en silencio:
 * sin largo original no hay techo contra el que comparar, y dejar pasar la
 * transformacion seria aceptar cualquier resultado. Se bloquea con un error de
 * negocio, que es lo que el proyecto ya hace cuando un producto no cumple el
 * contrato de su familia.
 *
 * <h2>Aritmetica</h2>
 *
 * {@link BigDecimal} con {@code compareTo}, igual que la regla de medidas con
 * orificio: el dominio no fija una escala y {@code double} arrastraria el error
 * de coma flotante en los decimales.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransformationLengthValidator {

    /**
     * Nombre canonico del atributo. Es el MISMO de la regla de medidas con
     * orificio y se lee de alli a proposito: las dos reglas hablan del mismo
     * {@code largo}, y tener dos constantes permitiria que una se renombrara sin
     * la otra.
     */
    public static final String LENGTH_ATTRIBUTE = HoleMeasurementValidator.LENGTH_ATTRIBUTE;

    static final String EXCEEDS_MESSAGE_PREFIX =
            "El largo resultante no puede ser mayor al largo original del producto";
    static final String MISSING_INPUT_MESSAGE =
            "El producto de entrada no tiene un largo valido y no se puede transformar.";

    /**
     * {@code true} cuando la familia declara {@code largo} como numero. Es la
     * unica condicion de activacion.
     */
    public static boolean applies(Family family) {
        return declaredAttribute(family, LENGTH_ATTRIBUTE)
                .map(attribute -> attribute.getDataType() == AttributeDataType.NUMBER)
                .orElse(false);
    }

    /**
     * Comprueba la regla sobre los atributos EFECTIVOS de la pieza resultante.
     * No hace nada si la familia no declara {@code largo} numerico.
     *
     * @param family              familia de la pieza de entrada
     * @param inputAttributes     atributos del Product de entrada, sin tocar
     * @param effectiveAttributes atributos que tendra la pieza resultante
     * @throws ValidationException con el error atado a la clave canonica de
     *                             {@code largo}, para que el cliente pueda
     *                             senalar el campo exacto
     */
    public static void validate(
            Family family,
            Map<String, Object> inputAttributes,
            Map<String, Object> effectiveAttributes) {
        if (!applies(family)) {
            return;
        }

        String lengthKey = canonicalKey(family, LENGTH_ATTRIBUTE);
        BigDecimal original = readDecimal(inputAttributes, LENGTH_ATTRIBUTE);

        // Sin largo original no hay techo: no se deja pasar en silencio.
        if (original == null) {
            throw new ValidationException(
                    MISSING_INPUT_MESSAGE,
                    Map.of(lengthKey, MISSING_INPUT_MESSAGE));
        }

        BigDecimal result = readDecimal(effectiveAttributes, LENGTH_ATTRIBUTE);

        // El resultado hereda el largo de la entrada cuando nadie lo pisa, asi
        // que solo puede faltar si el efectivo se construyo sin el; ahi no hay
        // nada que comparar y manda el resto de la validacion de atributos.
        if (result == null) {
            return;
        }

        if (result.compareTo(original) > 0) {
            String message = EXCEEDS_MESSAGE_PREFIX + " (" + plain(original) + ").";
            throw new ValidationException(message, Map.of(lengthKey, message));
        }
    }

    /** Sin notacion exponencial: {@code 2E+2} no es un largo legible. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ── Lectura de atributos (mismas convenciones que la regla de orificio) ──

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

    /**
     * El valor como {@link BigDecimal} exacto, o {@code null} si no es un numero.
     * El JSONB no distingue el caso de la clave, y los {@link Number} se leen por
     * su representacion en texto para no arrastrar el error de
     * {@code doubleValue()} ({@code new BigDecimal(0.1)} != 0.1).
     */
    private static BigDecimal readDecimal(Map<String, Object> attributes, String name) {
        if (attributes == null || name == null) {
            return null;
        }
        Object value = attributes.containsKey(name)
                ? attributes.get(name)
                : attributes.entrySet()
                        .stream()
                        .filter(entry -> entry.getKey() != null
                                && entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);

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
