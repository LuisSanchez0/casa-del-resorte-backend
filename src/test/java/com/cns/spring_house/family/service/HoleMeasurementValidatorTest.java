package com.cns.spring_house.family.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * La regla de medidas con orificio, aislada de producto y transformacion.
 *
 * Es 100% dirigida por los atributos que declara la familia: nunca mira el
 * nombre, el codigo, el id ni la categoria. Una familia que gestione el
 * conjunto completo desde el alta de atributos la hereda; cualquier otra queda
 * intacta.
 */
class HoleMeasurementValidatorTest {

    // ── Deteccion dinamica ────────────────────────────────────────────

    @Test
    void appliesWhenTheFamilyDeclaresTheCompleteSetWithTheRightTypes() {
        assertThat(HoleMeasurementValidator.applies(fullSetFamily())).isTrue();
    }

    @Test
    void doesNotApplyWhenASideLengthIsMissing() {
        Family family = familyDeclaring(
                attribute("largo", AttributeDataType.NUMBER),
                attribute("tieneOrificio", AttributeDataType.BOOLEAN),
                attribute("largoIzquierdo", AttributeDataType.NUMBER));

        assertThat(HoleMeasurementValidator.applies(family)).isFalse();
    }

    @Test
    void doesNotApplyWhenASideLengthIsDeclaredWithAnotherType() {
        Family family = familyDeclaring(
                attribute("largo", AttributeDataType.NUMBER),
                attribute("tieneOrificio", AttributeDataType.BOOLEAN),
                attribute("largoIzquierdo", AttributeDataType.STRING),
                attribute("largoDerecho", AttributeDataType.NUMBER));

        assertThat(HoleMeasurementValidator.applies(family)).isFalse();
    }

    @Test
    void theSetIsDetectedIgnoringCase() {
        Family family = familyDeclaring(
                attribute("LARGO", AttributeDataType.NUMBER),
                attribute("TieneOrificio", AttributeDataType.BOOLEAN),
                attribute("LargoIzquierdo", AttributeDataType.NUMBER),
                attribute("LARGODERECHO", AttributeDataType.NUMBER));

        assertThat(HoleMeasurementValidator.applies(family)).isTrue();
    }

    @Test
    void aFamilyOutsideTheSetIsNeverTouched() {
        Family family = familyDeclaring(
                attribute("largo", AttributeDataType.NUMBER),
                attribute("ancho", AttributeDataType.NUMBER),
                attribute("tieneOrificio", AttributeDataType.BOOLEAN));

        assertThatCode(() -> HoleMeasurementValidator.validate(
                family, attributes("largo", 100, "ancho", 20, "tieneOrificio", true)))
                .doesNotThrowAnyException();
    }

    // ── tieneOrificio == false ───────────────────────────────────────

    @Test
    void withoutHoleTheSideLengthsAreNotRequired() {
        assertThatCode(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutHoleStaleSideLengthsDoNotBlockAndAreLeftUntouched() {
        Map<String, Object> effective = attributes(
                "largo", 100, "tieneOrificio", false,
                "largoIzquierdo", 999, "largoDerecho", 999);

        assertThatCode(() -> HoleMeasurementValidator.validate(fullSetFamily(), effective))
                .doesNotThrowAnyException();
        assertThat(effective)
                .containsEntry("largoIzquierdo", 999)
                .containsEntry("largoDerecho", 999);
    }

    // ── tieneOrificio == true: la suma debe ser EXACTAMENTE el largo ─

    @ParameterizedTest(name = "{0} + {1} == {2}")
    @CsvSource({
            "45, 55, 100",
            "50, 50, 100",
            "45.1, 54.9, 100",
            "1, 199, 200",
            "0.25, 0.75, 1",
    })
    void withHoleASumExactlyEqualToTheTotalPasses(String left, String right, String total) {
        assertThatCode(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes(
                        "largo", new BigDecimal(total),
                        "tieneOrificio", true,
                        "largoIzquierdo", new BigDecimal(left),
                        "largoDerecho", new BigDecimal(right))))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} + {1} != {2} -> rechazado")
    @CsvSource({
            "40, 55, 100",
            "40, 50, 100",
            "60, 50, 100",
            "45.1, 54.8, 100",
            "45.11, 54.9, 100",
    })
    void withHoleASumThatIsNotExactlyTheTotalIsRejected(String left, String right, String total) {
        assertThatThrownBy(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes(
                        "largo", new BigDecimal(total),
                        "tieneOrificio", true,
                        "largoIzquierdo", new BigDecimal(left),
                        "largoDerecho", new BigDecimal(right))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");
    }

    @Test
    void withHoleAZeroSideLengthIsRejectedEvenIfTheOtherEqualsTheTotal() {
        assertThatThrownBy(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", 200, "tieneOrificio", true,
                        "largoIzquierdo", 200, "largoDerecho", 0)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo derecho debe ser mayor a 0.");

        assertThat(errorsOf(fullSetFamily(),
                attributes("largo", 200, "tieneOrificio", true,
                        "largoIzquierdo", 0, "largoDerecho", 200)))
                .containsEntry("largoIzquierdo", "El largo izquierdo debe ser mayor a 0.");
    }

    @Test
    void withHoleATotalOfZeroIsRejected() {
        assertThat(errorsOf(fullSetFamily(),
                attributes("largo", 0, "tieneOrificio", true,
                        "largoIzquierdo", 0, "largoDerecho", 0)))
                .containsEntry("largo", "El largo total debe ser mayor a 0.");
    }

    @Test
    void aDecimalSumNotExactlyTheTotalIsRejectedWithoutFloatingPointDrift() {
        // 45.1 + 54.9 == 100 exacto en BigDecimal: debe PASAR.
        assertThatCode(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", new BigDecimal("100"), "tieneOrificio", true,
                        "largoIzquierdo", new BigDecimal("45.1"),
                        "largoDerecho", new BigDecimal("54.9"))))
                .doesNotThrowAnyException();
        // 45.10 + 54.91 == 100.01: debe FALLAR.
        assertThatThrownBy(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", new BigDecimal("100"), "tieneOrificio", true,
                        "largoIzquierdo", new BigDecimal("45.10"),
                        "largoDerecho", new BigDecimal("54.91"))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La suma del largo izquierdo y derecho debe ser exactamente igual al largo total.");
    }

    // ── tieneOrificio == true: obligatorios y mayores a 0 ────────────

    @Test
    void withHoleAMissingLeftLengthIsRejected() {
        assertThatThrownBy(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", true, "largoDerecho", 55)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debe indicar el largo izquierdo cuando el producto tiene orificio.");

        assertThat(errorsOf(fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", true, "largoDerecho", 55)))
                .containsKey("largoIzquierdo");
    }

    @Test
    void withHoleAMissingRightLengthIsRejected() {
        assertThatThrownBy(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", true, "largoIzquierdo", 45)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Debe indicar el largo derecho cuando el producto tiene orificio.");

        assertThat(errorsOf(fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", true, "largoIzquierdo", 45)))
                .containsKey("largoDerecho");
    }

    @Test
    void withHoleBothMissingSideLengthsAreReported() {
        assertThat(errorsOf(fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", true)))
                .containsKeys("largoIzquierdo", "largoDerecho");
    }

    @Test
    void withHoleANegativeSideLengthIsRejected() {
        assertThatThrownBy(() -> HoleMeasurementValidator.validate(
                fullSetFamily(),
                attributes("largo", 100, "tieneOrificio", true,
                        "largoIzquierdo", -5, "largoDerecho", 55)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El largo izquierdo debe ser mayor a 0.");
    }

    @Test
    void withHoleAMissingTotalLengthIsRejected() {
        assertThat(errorsOf(fullSetFamily(),
                attributes("tieneOrificio", true, "largoIzquierdo", 45, "largoDerecho", 55)))
                .containsKey("largo");
    }

    @Test
    void theErrorKeysUseTheSpellingTheFamilyDeclares() {
        Family family = familyDeclaring(
                attribute("LARGO", AttributeDataType.NUMBER),
                attribute("tieneOrificio", AttributeDataType.BOOLEAN),
                attribute("LargoIzquierdo", AttributeDataType.NUMBER),
                attribute("LargoDerecho", AttributeDataType.NUMBER));

        assertThat(errorsOf(family, attributes("LARGO", 100, "tieneOrificio", true)))
                .containsKeys("LargoIzquierdo", "LargoDerecho");
    }

    // ── Auxiliares ───────────────────────────────────────────────────

    private static Map<String, String> errorsOf(Family family, Map<String, Object> attributes) {
        Throwable thrown = catchThrowable(
                () -> HoleMeasurementValidator.validate(family, attributes));
        assertThat(thrown).isInstanceOf(ValidationException.class);
        return ((ValidationException) thrown).getErrors();
    }

    private static Family fullSetFamily() {
        return familyDeclaring(
                attribute("largo", AttributeDataType.NUMBER),
                attribute("tieneOrificio", AttributeDataType.BOOLEAN),
                attribute("largoIzquierdo", AttributeDataType.NUMBER),
                attribute("largoDerecho", AttributeDataType.NUMBER));
    }

    private static Attribute attribute(String name, AttributeDataType dataType) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .dataType(dataType)
                .isRequired(true)
                .build();
    }

    private static Family familyDeclaring(Attribute... declared) {
        return Family.builder()
                .id(UUID.randomUUID())
                .name("FAMILIA DE PRUEBA")
                .category(FamilyCategory.SPECIAL)
                .attributes(new ArrayList<>(List.of(declared)))
                .build();
    }

    private static Map<String, Object> attributes(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

}
