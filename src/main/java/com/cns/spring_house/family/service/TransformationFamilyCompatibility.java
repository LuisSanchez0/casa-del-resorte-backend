package com.cns.spring_house.family.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.cns.spring_house.family.entity.Family;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Que familia puede ser el RESULTADO de transformar una pieza de otra familia.
 *
 * <h2>La regla general no cambia</h2>
 *
 * Transformar es modificar una pieza que ya existe: se corta, se rectifica, se
 * le abre un orificio. Por eso el resultado sigue siendo, por defecto, una
 * pieza de la MISMA familia. Elegir un producto de salida de otra familia
 * cambiaria en silencio lo que la unidad es, y eso se sigue rechazando.
 *
 * <h2>La excepcion de dominio</h2>
 *
 * <pre>
 * MATERIAL LISO -> HOJA DE RESORTE
 * </pre>
 *
 * Las dos familias comparten los mismos atributos de transformacion —{@code
 * largo}, {@code tieneOrificio} y los dos largos de cada lado—, asi que una
 * pieza fisica de material liso SI puede acabar siendo una hoja de resorte:
 * es literalmente lo que hace el taller al cortarla y perforarla.
 *
 * La excepcion es DIRIGIDA, no simetrica. Una hoja de resorte NO se convierte
 * en material liso por esta via: una vez cortada y perforada no vuelve a ser
 * material en bruto, y habilitar el sentido contrario "porque comparten
 * atributos" seria inventar una regla que el taller no pidio.
 *
 * <h2>Por que este es el unico sitio donde se decide</h2>
 *
 * La comprobacion vive aqui y no repartida por los manejadores para que
 * habilitar o revisar una compatibilidad sea leer UNA tabla, y para que el
 * endpoint que alimenta al frontend y el validador que autoriza la peticion no
 * puedan discrepar: los dos preguntan a {@link #isAllowed}.
 *
 * <h2>Como se identifica cada familia</h2>
 *
 * Por {@link Family#getCode()} contra un conjunto de codigos aceptados y, si no
 * casa ninguno, por {@link Family#getName()} canonico. Las dos columnas son
 * UNIQUE NOT NULL en el esquema (V5), asi que cualquiera de las dos identifica
 * una sola fila.
 *
 * El conjunto de codigos existe porque el codigo NO es igual en todas las
 * instalaciones y esto ya esta documentado en el propio proyecto: la migracion
 * V22 dice que V6 siembra la hoja de resorte con {@code 'HR'} mientras que la
 * base de trabajo tiene esa misma familia con {@code 'HOJA_RESORTE'}, y que el
 * NOMBRE si coincide en ambas. Por eso V22 apunta a la familia con
 * {@code (code = 'HR' OR name = 'HOJA DE RESORTE')}, y esta clase usa
 * exactamente el mismo criterio: aceptar las grafias de codigo conocidas y
 * dejar el nombre canonico como respaldo es lo que hace que la regla funcione
 * en las dos bases sin depender de cual de las dos se este mirando.
 *
 * El nombre es el ULTIMO recurso, nunca el primero, y no se compara contra
 * texto escrito por el usuario en ningun flujo: solo contra el valor que la
 * propia fila de {@code family} tiene guardado.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransformationFamilyCompatibility {

    /**
     * Las familias que esta regla conoce, con las claves estables por las que se
     * las reconoce. Anadir una familia al dominio no obliga a tocar nada aqui:
     * lo que no aparezca en esta tabla simplemente no tiene excepciones y se
     * comporta como siempre (solo su propia familia).
     */
    private enum DomainFamily {

        /** V6 la siembra con code 'ML'. */
        MATERIAL_LISO(Set.of("ML", "MATERIAL_LISO"), "MATERIAL LISO"),

        /** V6 la siembra con code 'HR'; la base de trabajo usa 'HOJA_RESORTE' (ver V22). */
        HOJA_DE_RESORTE(Set.of("HR", "HOJA_RESORTE"), "HOJA DE RESORTE");

        private final Set<String> codes;
        private final String canonicalName;

        DomainFamily(Set<String> codes, String canonicalName) {
            this.codes = codes;
            this.canonicalName = canonicalName;
        }

        boolean matches(Family family) {
            String code = normalize(family.getCode());
            if (code != null && codes.stream().anyMatch(known -> known.equals(code))) {
                return true;
            }
            String name = normalize(family.getName());
            return name != null && name.equals(canonicalName);
        }
    }

    /**
     * Las excepciones, en el unico sentido en que se han pedido.
     *
     * Leer la tabla como "una pieza de la familia CLAVE tambien puede acabar
     * siendo una pieza de cualquiera de las familias del VALOR". Que
     * {@code HOJA_DE_RESORTE} no aparezca como clave es lo que mantiene la
     * regla dirigida.
     */
    private static final Map<DomainFamily, Set<DomainFamily>> EXTRA_TARGETS = Map.of(
            DomainFamily.MATERIAL_LISO, Set.of(DomainFamily.HOJA_DE_RESORTE));

    /**
     * {@code true} cuando una pieza de {@code inputFamily} puede transformarse
     * en una pieza de {@code outputFamily}.
     *
     * Siempre cierto para la misma familia —la regla de siempre— y ademas para
     * los pares declarados en {@link #EXTRA_TARGETS}.
     *
     * Una familia sin id se considera no identificable y no autoriza nada: sin
     * identidad no hay forma de afirmar que el destino es el correcto, y en una
     * comprobacion de autorizacion la duda se resuelve negando.
     */
    public static boolean isAllowed(Family inputFamily, Family outputFamily) {
        if (inputFamily == null || outputFamily == null) {
            return false;
        }
        UUID inputId = inputFamily.getId();
        UUID outputId = outputFamily.getId();
        if (inputId == null || outputId == null) {
            return false;
        }
        if (inputId.equals(outputId)) {
            return true;
        }
        return isExtraTarget(inputFamily, outputFamily);
    }

    /**
     * {@code true} cuando el par esta permitido pero NO es la misma familia, es
     * decir cuando la pieza resultante cambia de familia. Los llamadores lo usan
     * para aplicar a la salida lo que su propia familia declara en lugar de lo
     * que declaraba la entrada.
     */
    public static boolean isCrossFamily(Family inputFamily, Family outputFamily) {
        return isAllowed(inputFamily, outputFamily)
                && !sameFamily(inputFamily, outputFamily);
    }

    /** Igualdad por id, la unica identidad fiable entre dos filas cargadas. */
    public static boolean sameFamily(Family left, Family right) {
        if (left == null || right == null) {
            return false;
        }
        UUID leftId = left.getId();
        return leftId != null && leftId.equals(right.getId());
    }

    /**
     * De un catalogo de familias, las que una pieza de {@code inputFamily} puede
     * llegar a ser: la suya primero y despues las compatibles, en el orden en
     * que llegan.
     *
     * Existe para que el frontend no tenga que conocer ningun codigo ni ningun
     * nombre: pregunta por los destinos de la familia que tiene delante y busca
     * productos en esos. Se resuelve sobre una lista ya cargada —el catalogo de
     * familias cabe entero en una consulta— en vez de una consulta por familia.
     */
    public static List<Family> targetsOf(Family inputFamily, List<Family> catalog) {
        if (inputFamily == null || catalog == null) {
            return List.of();
        }
        Set<Family> targets = new LinkedHashSet<>();
        catalog.stream()
                .filter(candidate -> sameFamily(inputFamily, candidate))
                .forEach(targets::add);
        catalog.stream()
                .filter(candidate -> isCrossFamily(inputFamily, candidate))
                .forEach(targets::add);
        return List.copyOf(targets);
    }

    private static boolean isExtraTarget(Family inputFamily, Family outputFamily) {
        Optional<DomainFamily> input = identify(inputFamily);
        Optional<DomainFamily> output = identify(outputFamily);
        if (input.isEmpty() || output.isEmpty()) {
            return false;
        }
        return EXTRA_TARGETS
                .getOrDefault(input.get(), Set.of())
                .contains(output.get());
    }

    private static Optional<DomainFamily> identify(Family family) {
        return Arrays.stream(DomainFamily.values())
                .filter(candidate -> candidate.matches(family))
                .findFirst();
    }

    /**
     * Mayusculas y sin espacios sobrantes. El dominio escribe codigos y nombres
     * en mayusculas, pero una fila creada a mano por la API puede no hacerlo y
     * eso no la convierte en otra familia.
     */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

}
