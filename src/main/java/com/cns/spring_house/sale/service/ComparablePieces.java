package com.cns.spring_house.sale.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.cns.spring_house.sale.dto.AttributeDifferenceDTO;

/**
 * Que tan parecida es otra pieza del taller a la pedida, SIN ninguna regla de
 * precio inventada.
 *
 * El sistema no tiene una politica comercial que diga cuanto puede variar una
 * medida para que dos piezas "valgan lo mismo" —un largo distinto es mas o
 * menos material y otro corte, unos colochos de mas son mas mano de obra—. Por
 * eso aqui no hay porcentajes: solo dos clases, decididas por igualdad exacta.
 *
 * <ul>
 * <li>{@link Kind#EQUIVALENT}: misma familia y modelo (lo filtra la consulta)
 * y EXACTAMENTE las mismas caracteristicas, contando las de las dos piezas.
 * Es otro registro del catalogo con la misma especificacion (por ejemplo, el
 * mismo largo con otro nombre). Su precio puede proponerse, siempre rotulado
 * como referencia y a confirmar.</li>
 * <li>{@link Kind#SIMILAR}: todo igual salvo UNA medida numerica que las dos
 * declaran. No es la misma pieza: se muestra como contexto, con la diferencia
 * a la vista, y nunca se propone sola como precio.</li>
 * </ul>
 *
 * Cualquier otra diferencia —un tipo distinto (con o sin orificio), un texto,
 * una caracteristica que solo una de las dos declara, o dos medidas
 * distintas— la descarta: ya es otra pieza.
 *
 * El importe que se muestra es siempre el de la referencia TAL CUAL; no se
 * interpola ni se escala por la medida.
 */
public final class ComparablePieces {

    public enum Kind {
        EQUIVALENT,
        SIMILAR
    }

    /**
     * Una referencia aceptada.
     *
     * @param kind        equivalente o similar
     * @param differences en que difiere (vacio si es equivalente; una si es similar)
     * @param attribute   la medida que difiere; null si es equivalente
     * @param gap         referencia menos pedida en esa medida (negativa: mas
     *                    pequena); cero si es equivalente
     */
    public record Comparison(
            Kind kind,
            List<AttributeDifferenceDTO> differences,
            String attribute,
            BigDecimal gap) {
    }

    private ComparablePieces() {
    }

    /**
     * Compara la pieza pedida con una candidata. Vacio si no es ni equivalente
     * ni similar. Sin ninguna caracteristica que comparar no hay referencia:
     * dos piezas de la misma familia sin caracteristicas pueden no tener nada
     * que ver.
     */
    public static Optional<Comparison> compare(
            Map<String, Object> requested, Map<String, Object> reference) {
        Map<String, Object> wanted = present(requested);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> found = present(reference);

        List<String> keys = new ArrayList<>(wanted.keySet());
        found.keySet().stream().filter(key -> !wanted.containsKey(key)).forEach(keys::add);

        List<AttributeDifferenceDTO> differences = new ArrayList<>();
        String differingAttribute = null;
        BigDecimal gap = BigDecimal.ZERO;
        for (String key : keys) {
            Object a = wanted.get(key);
            Object b = found.get(key);
            if (sameValue(a, b)) {
                continue;
            }
            // Solo una medida numerica que declaran las dos piezas puede
            // diferir; todo lo demas es otra pieza.
            if (!(a instanceof Number left) || !(b instanceof Number right) || !differences.isEmpty()) {
                return Optional.empty();
            }
            differences.add(new AttributeDifferenceDTO(displayKey(requested, reference, key), a, b));
            differingAttribute = key;
            gap = decimal(right).subtract(decimal(left));
        }
        return Optional.of(differences.isEmpty()
                ? new Comparison(Kind.EQUIVALENT, List.of(), null, BigDecimal.ZERO)
                : new Comparison(Kind.SIMILAR, List.copyOf(differences), differingAttribute, gap));
    }

    static boolean sameValue(Object wanted, Object found) {
        if (wanted == null || found == null) {
            return wanted == found;
        }
        if (wanted instanceof Number a && found instanceof Number b) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        if (wanted instanceof Boolean || found instanceof Boolean) {
            return wanted.equals(found);
        }
        return String.valueOf(wanted).trim().toUpperCase(Locale.ROOT)
                .equals(String.valueOf(found).trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Las caracteristicas con valor, por nombre sin distinguir mayusculas: el
     * catalogo historico tiene `LARGO` y `largo` (ver {@code SpecialPieceRules}).
     */
    private static Map<String, Object> present(Map<String, Object> attributes) {
        Map<String, Object> present = new LinkedHashMap<>();
        if (attributes == null) {
            return present;
        }
        attributes.forEach((key, value) -> {
            if (key != null && value != null) {
                present.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
            }
        });
        return present;
    }

    /** El nombre tal como lo escribio la pieza pedida, o el de la referencia. */
    private static String displayKey(
            Map<String, Object> requested, Map<String, Object> reference, String lowerKey) {
        for (Map<String, Object> source : List.of(
                requested == null ? Map.<String, Object>of() : requested,
                reference == null ? Map.<String, Object>of() : reference)) {
            for (String key : source.keySet()) {
                if (key != null && key.toLowerCase(Locale.ROOT).equals(lowerKey)) {
                    return key;
                }
            }
        }
        return lowerKey;
    }

    private static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal value ? value : new BigDecimal(number.toString());
    }

}
