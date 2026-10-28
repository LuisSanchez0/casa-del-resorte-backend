package com.cns.spring_house.transformation.handler;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.cns.spring_house.family.entity.Family;

/**
 * Reglas de una pieza especial que no dependen de ningun repositorio: como se
 * llama una pieza que crea el sistema y cuando una transformacion deja
 * sobrante.
 *
 * Viven aparte del manejador para que la comanda las aplique IGUAL que la
 * transformacion: el vendedor que pide una pieza nueva y el operador que la
 * fabrica no pueden obtener dos nombres distintos ni dos lecturas distintas de
 * si sobra material.
 */
public final class SpecialPieceRules {

    static final String LENGTH_ATTRIBUTE = "LARGO";
    static final String HOLE_ATTRIBUTE = "tieneOrificio";

    private SpecialPieceRules() {
    }

    /**
     * Nombre legible para las piezas que el sistema crea solo (sobrantes,
     * transformaciones personalizadas y piezas pedidas que aun no existian).
     * Antes todas se llamaban igual ("PRODUCTO RESULTANTE"), lo que las hacia
     * indistinguibles en el catalogo.
     */
    public static String generatedName(Family family, Map<String, Object> attributes) {
        Double length = numericAttribute(attributes, LENGTH_ATTRIBUTE);
        String base = family.getName() == null ? "PRODUCTO RESULTANTE" : family.getName();

        if (length == null) {
            return base;
        }

        String formattedLength = length == Math.rint(length)
                ? String.valueOf((long) (double) length)
                : String.valueOf((double) length);

        return base + " " + formattedLength;
    }

    /**
     * `sku` es UNIQUE: se genera uno corto y unico. La reutilizacion de productos
     * equivalentes NO depende del SKU sino de familia + modelo + atributos.
     */
    public static String generatedSku() {
        return "AUTO-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();
    }

    /**
     * Decide si hay sobrante y de que largo, sin crear nada: pieza con orificio
     * no deja sobrante, y el recorte debe llegar al menos a la mitad.
     */
    public static Optional<Double> remainderLength(
            Map<String, Object> inputAttributes, Map<String, Object> outputAttributes) {
        boolean hasHole = Boolean.TRUE.equals(
                booleanAttribute(inputAttributes, HOLE_ATTRIBUTE));
        if (hasHole) {
            return Optional.empty();
        }
        Double inputLength = numericAttribute(inputAttributes, LENGTH_ATTRIBUTE);
        Double outputLength = numericAttribute(outputAttributes, LENGTH_ATTRIBUTE);
        if (inputLength == null || outputLength == null) {
            return Optional.empty();
        }
        double difference = (inputLength - outputLength);
        if (difference < inputLength / 2) {
            return Optional.empty();
        }
        return Optional.of(difference);
    }

    /**
     * Busca el atributo primero por coincidencia exacta y, si no existe, por
     * nombre equivalente ignorando mayusculas y minusculas.
     */
    static Object attributeIgnoreCase(
            Map<String, Object> attributes, String attributeName) {
        if (attributes == null || attributeName == null) {
            return null;
        }
        if (attributes.containsKey(attributeName)) {
            return attributes.get(attributeName);
        }
        return attributes.entrySet()
                .stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(attributeName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Los valores del JSONB llegan al Map como Integer, Long, Double o
     * BigDecimal segun el literal almacenado. Los textos numericos no se
     * convierten: para la familia el atributo seria de tipo STRING.
     */
    public static Double numericAttribute(
            Map<String, Object> attributes, String attributeName) {
        Object value = attributeIgnoreCase(attributes, attributeName);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    static Boolean booleanAttribute(
            Map<String, Object> attributes, String attributeName) {
        Object value = attributeIgnoreCase(attributes, attributeName);
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

}
