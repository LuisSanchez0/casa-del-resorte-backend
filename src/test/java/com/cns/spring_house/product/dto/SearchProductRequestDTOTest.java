package com.cns.spring_house.product.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cns.spring_house.shared.exception.ValidationException;

/**
 * Que criterios son PARAMETROS y cuales son caracteristicas del producto.
 *
 * Esta distincion no es cosmetica: `GET /v1/products` acepta la query entera y
 * convierte en filtro sobre el JSONB todo lo que no reconozca. Un nombre que se
 * olvide de reservar no falla ni avisa —se traduce en
 * `jsonb_extract_path_text(attributes, '<nombre>') = '<valor>'`— y lo unico que
 * se ve desde la caja es una busqueda que de pronto no devuelve nada.
 */
class SearchProductRequestDTOTest {

    private static Map<String, Object> filters(String... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    // ── Parametros reservados ────────────────────────────────────

    @Test
    void brandIsReadAsACriterionAndNotAsAnAttribute() {
        UUID brand = UUID.randomUUID();

        SearchProductRequestDTO request = SearchProductRequestDTO.from(
                filters("brand", brand.toString()));

        assertThat(request.brand()).isEqualTo(brand);
        assertThat(request.attributes()).doesNotContainKey("brand");
        assertThat(request.attributes()).isEmpty();
    }

    @Test
    void theFourReservedNamesNeverReachTheAttributes() {
        UUID family = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID model = UUID.randomUUID();

        SearchProductRequestDTO request = SearchProductRequestDTO.from(filters(
                "q", "muelle",
                "family", family.toString(),
                "brand", brand.toString(),
                "model", model.toString()));

        assertThat(request.q()).isEqualTo("muelle");
        assertThat(request.family()).isEqualTo(family);
        assertThat(request.brand()).isEqualTo(brand);
        assertThat(request.model()).isEqualTo(model);
        assertThat(request.attributes()).isEmpty();
    }

    /**
     * La marca convive con las caracteristicas: quitarla del mapa no puede
     * llevarse por delante los filtros que si son atributos.
     */
    @Test
    void theRemainingParametersAreStillAttributes() {
        SearchProductRequestDTO request = SearchProductRequestDTO.from(filters(
                "brand", UUID.randomUUID().toString(),
                "largo", "15",
                "tieneOrificio", "true"));

        assertThat(request.attributes())
                .containsOnly(
                        Map.entry("largo", "15"),
                        Map.entry("tieneOrificio", "true"));
    }

    // ── Valores mal escritos ─────────────────────────────────────

    /**
     * Una marca ilegible se RECHAZA. Antes se descartaba en silencio y la
     * busqueda seguia adelante sin filtro de marca, que es la peor de las dos
     * respuestas posibles: desde la caja es identica a haber buscado bien, asi
     * que el vendedor se lleva a la pantalla piezas de todas las marcas
     * creyendo que son las de una.
     */
    @Test
    void aBrandThatIsNotAUuidIsRejected() {
        Map<String, Object> filters = filters("brand", "toyota");

        assertThatThrownBy(() -> SearchProductRequestDTO.from(filters))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El identificador de la marca no es válido.");
    }

    /**
     * Lo que manda una caja rota. Los tres son texto que un cliente javascript
     * envia sin querer al serializar un valor ausente, y ninguno puede pasar
     * por identificador.
     */
    @ParameterizedTest
    @ValueSource(strings = { "undefined", "null", "[object Object]", "1-1-1-1-1", "toyota" })
    void malformedBrandValuesAreRejected(String malformed) {
        Map<String, Object> filters = filters("brand", malformed);

        assertThatThrownBy(() -> SearchProductRequestDTO.from(filters))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * Rechazar no puede ser una via para que la clave sobreviva: si la marca
     * ilegible se quedara en el mapa y alguien capturase la excepcion mas
     * arriba, volveria a viajar como caracteristica.
     */
    @ParameterizedTest
    @ValueSource(strings = { "toyota", "undefined", "null", "[object Object]" })
    void aMalformedBrandNeverBecomesAnAttribute(String malformed) {
        Map<String, Object> filters = filters("brand", malformed);

        assertThatThrownBy(() -> SearchProductRequestDTO.from(filters))
                .isInstanceOf(ValidationException.class);
        assertThat(filters).doesNotContainKey("brand");
    }

    /**
     * La misma regla vale para los otros dos identificadores reservados: el
     * fallo silencioso estaba en el lector compartido, no en la marca.
     */
    @Test
    void aFamilyAndAModelThatAreNotUuidsAreAlsoRejected() {
        assertThatThrownBy(() -> SearchProductRequestDTO.from(filters("family", "hojas")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El identificador de la familia no es válido.");

        assertThatThrownBy(() -> SearchProductRequestDTO.from(filters("model", "corolla")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El identificador del modelo no es válido.");
    }

    /**
     * Un UUID BIEN ESCRITO que no existe no es un error de quien pregunta: la
     * busqueda se hace y simplemente no encuentra nada. Solo se rechaza lo que
     * no tiene forma de identificador.
     */
    @Test
    void aWellFormedBrandThatMatchesNothingIsStillAValidCriterion() {
        UUID unknown = UUID.randomUUID();

        SearchProductRequestDTO request = SearchProductRequestDTO.from(
                filters("brand", unknown.toString()));

        assertThat(request.brand()).isEqualTo(unknown);
    }

    /**
     * `?brand=` es lo que manda un formulario que no ha elegido marca. Se lee
     * como ausente —la convencion del resto de DTOs del proyecto—, nunca como
     * caracteristica.
     */
    @Test
    void aBlankBrandIsReadAsNoFilter() {
        Map<String, Object> filters = filters("brand", "", "q", "hoja");

        SearchProductRequestDTO request = SearchProductRequestDTO.from(filters);

        assertThat(request.brand()).isNull();
        assertThat(request.attributes()).doesNotContainKey("brand");
        assertThat(request.attributes()).isEmpty();
    }

    @Test
    void anAbsentBrandIsSimplyNoFilter() {
        SearchProductRequestDTO request = SearchProductRequestDTO.from(
                filters("q", "hoja"));

        assertThat(request.brand()).isNull();
        assertThat(request.family()).isNull();
        assertThat(request.model()).isNull();
        assertThat(request.attributes()).isEmpty();
    }
}
