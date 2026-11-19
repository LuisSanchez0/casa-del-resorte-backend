package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.sale.service.ComparablePieces.Kind;

/**
 * Equivalente o similar, por igualdad exacta y sin ningun porcentaje: el
 * sistema no tiene una regla comercial que diga cuanto puede variar una medida
 * para que dos piezas valgan lo mismo. Ver {@link ComparablePieces}.
 */
class ComparablePiecesTest {

    private static Map<String, Object> leaf(Object largo, Object orificio) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("largo", largo);
        attributes.put("tieneOrificio", orificio);
        return attributes;
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @Test
    void theSameCharacteristicsAreEquivalent() {
        assertThat(ComparablePieces.compare(leaf(90, false), leaf(90, false)))
                .hasValueSatisfying(comparison -> {
                    assertThat(comparison.kind()).isEqualTo(Kind.EQUIVALENT);
                    assertThat(comparison.differences()).isEmpty();
                    assertThat(comparison.attribute()).isNull();
                });
    }

    @Test
    void numbersAreComparedByValueNotByType() {
        assertThat(ComparablePieces.compare(leaf(90, false), leaf(90.0, false)))
                .hasValueSatisfying(c -> assertThat(c.kind()).isEqualTo(Kind.EQUIVALENT));
        assertThat(ComparablePieces.compare(leaf(new BigDecimal("90.00"), false), leaf(90L, false)))
                .hasValueSatisfying(c -> assertThat(c.kind()).isEqualTo(Kind.EQUIVALENT));
    }

    /** Sin porcentaje: cualquier distancia en UNA medida es "similar", nunca equivalente. */
    @Test
    void oneDifferentMeasureIsSimilarWhateverTheDistance() {
        for (int length : new int[] { 91, 110, 300, 5 }) {
            assertThat(ComparablePieces.compare(leaf(100, false), leaf(length, false)))
                    .as("largo %d", length)
                    .hasValueSatisfying(comparison -> {
                        assertThat(comparison.kind()).isEqualTo(Kind.SIMILAR);
                        assertThat(comparison.attribute()).isEqualTo("largo");
                        assertThat(comparison.differences()).singleElement().satisfies(difference -> {
                            assertThat(difference.attribute()).isEqualTo("largo");
                            assertThat(difference.requested()).isEqualTo(100);
                            assertThat(difference.reference()).isEqualTo(length);
                        });
                        assertThat(comparison.gap()).isEqualByComparingTo(String.valueOf(length - 100));
                    });
        }
    }

    @Test
    void aDifferentTypeOrTextIsAnotherPiece() {
        assertThat(ComparablePieces.compare(leaf(90, false), leaf(90, true))).isEmpty();
        assertThat(ComparablePieces.compare(
                Map.of("posicion", "DELANTERA"), Map.of("posicion", "TRASERA"))).isEmpty();
    }

    @Test
    void textIsComparedWithoutCaseOrSurroundingSpaces() {
        assertThat(ComparablePieces.compare(
                Map.of("posicion", "delantera "), Map.of("posicion", "DELANTERA")))
                .hasValueSatisfying(c -> assertThat(c.kind()).isEqualTo(Kind.EQUIVALENT));
    }

    @Test
    void twoDifferentMeasuresAreTooMany() {
        assertThat(ComparablePieces.compare(
                with(leaf(100, false), "ancho", 60), with(leaf(105, false), "ancho", 62))).isEmpty();
    }

    /** Lo que solo declara una de las dos piezas la hace otra pieza, en cualquier sentido. */
    @Test
    void aCharacteristicOnlyOneSideDeclaresRulesItOut() {
        assertThat(ComparablePieces.compare(with(leaf(90, false), "cantidadColochos", 2), leaf(90, false)))
                .isEmpty();
        assertThat(ComparablePieces.compare(leaf(90, false), with(leaf(90, false), "cantidadColochos", 2)))
                .isEmpty();
    }

    @Test
    void keysAreMatchedIgnoringCaseLikeTheHistoricCatalog() {
        assertThat(ComparablePieces.compare(Map.of("largo", 90), Map.of("LARGO", 90)))
                .hasValueSatisfying(c -> assertThat(c.kind()).isEqualTo(Kind.EQUIVALENT));
        assertThat(ComparablePieces.compare(Map.of("largo", 90), Map.of("LARGO", 95)))
                .hasValueSatisfying(c -> assertThat(c.differences().get(0).attribute()).isEqualTo("largo"));
    }

    @Test
    void nothingToCompareMeansNoReference() {
        assertThat(ComparablePieces.compare(Map.of(), leaf(90, false))).isEmpty();
        assertThat(ComparablePieces.compare(null, leaf(90, false))).isEmpty();
        Map<String, Object> onlyNulls = new HashMap<>();
        onlyNulls.put("largo", null);
        assertThat(ComparablePieces.compare(onlyNulls, leaf(90, false))).isEmpty();
    }

    @Test
    void curlCountsAreAMeasureLikeAnyOther() {
        assertThat(ComparablePieces.compare(
                with(leaf(90, false), "cantidadColochos", 0), with(leaf(90, false), "cantidadColochos", 2)))
                .hasValueSatisfying(c -> {
                    assertThat(c.kind()).isEqualTo(Kind.SIMILAR);
                    assertThat(c.attribute()).isEqualTo("cantidadcolochos");
                });
    }

}
