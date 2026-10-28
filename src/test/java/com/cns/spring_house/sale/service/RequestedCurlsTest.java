package com.cns.spring_house.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * La decision de colochos de una linea nueva, contra la pieza pedida: que se
 * exige, que se deriva y que se rechaza, sin base de datos.
 */
class RequestedCurlsTest {

    private static Product piece(boolean familyHasCurls, Object curls) {
        List<Attribute> declared = new ArrayList<>();
        declared.add(Attribute.builder().name("largo").dataType(AttributeDataType.NUMBER).isRequired(true).build());
        if (familyHasCurls) {
            declared.add(Attribute.builder()
                    .name("cantidadColochos").dataType(AttributeDataType.NUMBER).isRequired(false).build());
        }
        Family family = Family.builder()
                .code("HR").name("HOJA").requiresModel(false).category(FamilyCategory.SPECIAL)
                .attributes(declared)
                .build();
        Map<String, Object> attributes = new HashMap<>(Map.of("largo", 100));
        if (curls != null) {
            attributes.put("cantidadColochos", curls);
        }
        return Product.builder().name("HOJA 100").family(family).attributes(attributes).build();
    }

    @Test
    void aPieceThatDoesNotFixThemNeedsTheSellersYesOrNo() {
        Product open = piece(true, null);

        assertThatThrownBy(() -> RequestedCurls.forNewLine(open, null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Indique si la pieza lleva colochos");
        assertThat(RequestedCurls.forNewLine(open, true, 4)).isEqualTo(new RequestedCurls(true, 4));
        assertThat(RequestedCurls.forNewLine(open, false, null)).isEqualTo(new RequestedCurls(false, null));
        assertThat(RequestedCurls.forNewLine(open, false, 0)).isEqualTo(new RequestedCurls(false, null));
    }

    @Test
    void yesRequiresHowMany() {
        Product open = piece(true, null);

        assertThatThrownBy(() -> RequestedCurls.forNewLine(open, true, null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Indique cuántos colochos lleva la pieza");
        assertThatThrownBy(() -> RequestedCurls.forNewLine(open, true, 0)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aNewPieceOnlyTakesAWholeNonNegativeCount() {
        RequestedCurls.validateSpecified(Map.of("largo", 90));
        RequestedCurls.validateSpecified(Map.of("cantidadColochos", 4));
        RequestedCurls.validateSpecified(Map.of("cantidadColochos", 0));
        RequestedCurls.validateSpecified(Map.of("cantidadColochos", 4.0));
        for (Object bad : List.of(3.5, -2, "4", true)) {
            assertThatThrownBy(() -> RequestedCurls.validateSpecified(Map.of("cantidadColochos", bad)))
                    .as("cantidadColochos %s", bad)
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void impossibleCombinationsAreRejected() {
        Product open = piece(true, null);

        assertThatThrownBy(() -> RequestedCurls.forNewLine(open, false, 2)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RequestedCurls.forNewLine(open, true, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RequestedCurls.forNewLine(open, true, -1)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aPieceThatFixesThemDecidesAndCannotBeContradicted() {
        Product four = piece(true, 4);
        Product none = piece(true, 0);

        assertThat(RequestedCurls.forNewLine(four, null, null)).isEqualTo(new RequestedCurls(true, 4));
        assertThat(RequestedCurls.forNewLine(four, true, 4)).isEqualTo(new RequestedCurls(true, 4));
        assertThatThrownBy(() -> RequestedCurls.forNewLine(four, false, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RequestedCurls.forNewLine(four, true, 3)).isInstanceOf(ValidationException.class);
        assertThat(RequestedCurls.forNewLine(none, null, null)).isEqualTo(new RequestedCurls(false, null));
        assertThatThrownBy(() -> RequestedCurls.forNewLine(none, true, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aFamilyWithoutCurlsTakesNoDecision() {
        Product plain = piece(false, null);

        assertThat(RequestedCurls.forNewLine(plain, null, null)).isEqualTo(new RequestedCurls(null, null));
        assertThatThrownBy(() -> RequestedCurls.forNewLine(plain, true, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aLegacyLineIsReadFromItsProductOrStaysUnknown() {
        assertThat(RequestedCurls.of(null, null, Map.of("largo", 100))).isEqualTo(new RequestedCurls(null, null));
        assertThat(RequestedCurls.of(null, null, Map.of("cantidadColochos", 3))).isEqualTo(new RequestedCurls(true, 3));
        assertThat(RequestedCurls.of(null, null, Map.of("cantidadColochos", 0))).isEqualTo(new RequestedCurls(false, null));
        // La decision guardada manda sobre lo que diga el producto despues.
        assertThat(RequestedCurls.of(true, null, Map.of("cantidadColochos", 3))).isEqualTo(new RequestedCurls(true, null));
        assertThat(RequestedCurls.of(false, null, null).excludesCurls()).isTrue();
    }

}
