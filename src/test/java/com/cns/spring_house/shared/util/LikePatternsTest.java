package com.cns.spring_house.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Un parametro enlazado impide la inyeccion SQL, pero no le quita a `%` y `_`
 * su significado de comodin. Estas comprobaciones fijan que el texto de quien
 * busca viaje LITERAL.
 */
class LikePatternsTest {

    @Test
    void wrapsPlainTextWithWildcardsAndLowercasesIt() {
        assertThat(LikePatterns.containsIgnoreCase("550E84")).isEqualTo("%550e84%");
    }

    @Test
    void trimsTheSurroundingBlanks() {
        assertThat(LikePatterns.containsIgnoreCase("  acme  ")).isEqualTo("%acme%");
    }

    @Test
    void neutralisesThePercentSign() {
        assertThat(LikePatterns.containsIgnoreCase("50%")).isEqualTo("%50\\%%");
    }

    @Test
    void neutralisesTheUnderscore() {
        assertThat(LikePatterns.containsIgnoreCase("lote_1")).isEqualTo("%lote\\_1%");
    }

    /**
     * La barra se escapa PRIMERO. Al reves, las barras que introducen los otros
     * dos reemplazos se volverian a escapar y el patron dejaria de describir lo
     * que se escribio.
     */
    @Test
    void neutralisesTheEscapeCharacterItselfBeforeTheWildcards() {
        assertThat(LikePatterns.containsIgnoreCase("c\\norte")).isEqualTo("%c\\\\norte%");
        assertThat(LikePatterns.containsIgnoreCase("50%_\\")).isEqualTo("%50\\%\\_\\\\%");
    }

    @Test
    void anEmptyTextStillProducesAValidPattern() {
        assertThat(LikePatterns.containsIgnoreCase("")).isEqualTo("%%");
    }


    @Test
    void foldsAccentsSoAPlainSearchFindsAccentedText() {
        assertThat(LikePatterns.containsIgnoreCaseAndAccents("  Camión Pequeño "))
                .isEqualTo("%camion pequeno%");
        assertThat(LikePatterns.containsIgnoreCaseAndAccents("50%_x"))
                .isEqualTo("%50\\%\\_x%");
    }

    @Test
    void theTranslateTablesPairEveryAccentedLetterWithItsBase() {
        assertThat(LikePatterns.ACCENTED).hasSameSizeAs(LikePatterns.UNACCENTED);
        for (int index = 0; index < LikePatterns.ACCENTED.length(); index++) {
            String accented = String.valueOf(LikePatterns.ACCENTED.charAt(index));
            assertThat(LikePatterns.containsIgnoreCaseAndAccents(accented))
                    .isEqualTo("%" + LikePatterns.UNACCENTED.charAt(index) + "%");
        }
    }
}
