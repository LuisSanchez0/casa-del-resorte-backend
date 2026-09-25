package com.cns.spring_house.family.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;

/**
 * La tabla de compatibilidad entre familias, aislada del manejador.
 *
 * Aqui se fija lo que la regla significa: la misma familia siempre, MATERIAL
 * LISO -> HOJA DE RESORTE ademas, y nada mas. En particular se fija el sentido:
 * el par al reves NO queda habilitado de rebote.
 */
class TransformationFamilyCompatibilityTest {

    // ── La regla de siempre ────────────────────────────────────────────

    @Test
    void aFamilyIsAlwaysAllowedToProduceItsOwnPieces() {
        Family family = family("ML", "MATERIAL LISO");

        assertThat(TransformationFamilyCompatibility.isAllowed(family, family)).isTrue();
        assertThat(TransformationFamilyCompatibility.isCrossFamily(family, family)).isFalse();
    }

    @Test
    void twoUnrelatedFamiliesAreNeverCompatible() {
        Family input = family("ML", "MATERIAL LISO");
        Family unrelated = family("MAL", "MALETA");

        assertThat(TransformationFamilyCompatibility.isAllowed(input, unrelated)).isFalse();
    }

    /**
     * Una familia sin identidad no autoriza nada. Esto es una comprobacion de
     * autorizacion: ante la duda se niega, no se concede.
     */
    @Test
    void aFamilyWithoutIdAuthorizesNothing() {
        Family withoutId = Family.builder()
                .code("ML")
                .name("MATERIAL LISO")
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>())
                .build();
        Family leaf = family("HR", "HOJA DE RESORTE");

        assertThat(TransformationFamilyCompatibility.isAllowed(withoutId, leaf)).isFalse();
        assertThat(TransformationFamilyCompatibility.isAllowed(leaf, withoutId)).isFalse();
        assertThat(TransformationFamilyCompatibility.isAllowed(null, leaf)).isFalse();
        assertThat(TransformationFamilyCompatibility.isAllowed(leaf, null)).isFalse();
    }

    // ── La excepcion pedida ────────────────────────────────────────────

    @Nested
    class MaterialLisoBecomesALeafSpring {

        @Test
        void isAllowedWithTheCodesSeededByV6() {
            Family input = family("ML", "MATERIAL LISO");
            Family target = family("HR", "HOJA DE RESORTE");

            assertThat(TransformationFamilyCompatibility.isAllowed(input, target)).isTrue();
            assertThat(TransformationFamilyCompatibility.isCrossFamily(input, target)).isTrue();
        }

        /**
         * La base de trabajo tiene la hoja con code 'HOJA_RESORTE' en vez de
         * 'HR' —lo documenta la propia migracion V22—, y la regla tiene que
         * valer igual alli.
         */
        @Test
        void isAllowedWithTheCodeTheWorkingDatabaseUses() {
            Family input = family("ML", "MATERIAL LISO");
            Family target = family("HOJA_RESORTE", "HOJA DE RESORTE");

            assertThat(TransformationFamilyCompatibility.isAllowed(input, target)).isTrue();
        }

        /** Si el codigo no se reconoce, manda el nombre canonico de la fila. */
        @Test
        void isAllowedByCanonicalNameWhenTheCodeIsUnknown() {
            Family input = family("CODIGO-RARO-1", "MATERIAL LISO");
            Family target = family("CODIGO-RARO-2", "HOJA DE RESORTE");

            assertThat(TransformationFamilyCompatibility.isAllowed(input, target)).isTrue();
        }

        /** Una fila creada por la API en minusculas es la misma familia. */
        @Test
        void identificationIgnoresCaseAndSurroundingSpaces() {
            Family input = family(" ml ", "material liso");
            Family target = family("hr", "hoja de resorte");

            assertThat(TransformationFamilyCompatibility.isAllowed(input, target)).isTrue();
        }
    }

    // ── El sentido contrario NO se habilita ────────────────────────────

    /**
     * Compartir atributos no es motivo para el sentido inverso: una hoja ya
     * cortada y perforada no vuelve a ser material en bruto. Solo se pidio una
     * direccion y solo esa existe.
     */
    @Test
    void aLeafSpringDoesNotGainMaterialLisoTargets() {
        Family input = family("HR", "HOJA DE RESORTE");
        Family target = family("ML", "MATERIAL LISO");

        assertThat(TransformationFamilyCompatibility.isAllowed(input, target)).isFalse();
        assertThat(TransformationFamilyCompatibility.isCrossFamily(input, target)).isFalse();
    }

    @Test
    void materialLisoDoesNotGainUnrelatedTargets() {
        Family input = family("ML", "MATERIAL LISO");

        assertThat(TransformationFamilyCompatibility.isAllowed(
                input, family("MAL", "MALETA"))).isFalse();
        assertThat(TransformationFamilyCompatibility.isAllowed(
                input, family("RES", "RESORTE ESPIRAL"))).isFalse();
    }

    // ── Destinos para el selector del frontend ─────────────────────────

    @Nested
    class Targets {

        private final Family materialLiso = family("ML", "MATERIAL LISO");
        private final Family hojaResorte = family("HR", "HOJA DE RESORTE");
        private final Family maleta = family("MAL", "MALETA");
        private final List<Family> catalog = List.of(materialLiso, hojaResorte, maleta);

        @Test
        void materialLisoOffersItsOwnFamilyFirstAndThenTheLeafSpring() {
            assertThat(TransformationFamilyCompatibility.targetsOf(materialLiso, catalog))
                    .containsExactly(materialLiso, hojaResorte);
        }

        @Test
        void aLeafSpringOffersOnlyItsOwnFamily() {
            assertThat(TransformationFamilyCompatibility.targetsOf(hojaResorte, catalog))
                    .containsExactly(hojaResorte);
        }

        @Test
        void anUnrelatedFamilyOffersOnlyItsOwnFamily() {
            assertThat(TransformationFamilyCompatibility.targetsOf(maleta, catalog))
                    .containsExactly(maleta);
        }

        @Test
        void aFamilyMissingFromTheCatalogYieldsNothingItCannotProve() {
            assertThat(TransformationFamilyCompatibility.targetsOf(materialLiso, List.of()))
                    .isEmpty();
            assertThat(TransformationFamilyCompatibility.targetsOf(null, catalog))
                    .isEmpty();
        }
    }

    // ── Auxiliares ─────────────────────────────────────────────────────

    private static Family family(String code, String name) {
        return Family.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>())
                .build();
    }

}
