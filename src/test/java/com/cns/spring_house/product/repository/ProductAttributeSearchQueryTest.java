package com.cns.spring_house.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.testsupport.TestDatabases;

/**
 * El filtrado por CARACTERISTICA contra PostgreSQL de verdad.
 *
 * No sirve un mock aqui: lo que fallaba era exactamente la comparacion que hace
 * el motor. La especificacion bajaba a minusculas la COLUMNA y dejaba el
 * parametro tal cual, asi que `LOWER(name) LIKE '%MUELLE%'` no casaba nunca con
 * "MUELLE RECTIFICADO 60X10". Una prueba con repositorio simulado habria pasado
 * igual, porque el defecto vivia en el SQL.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ProductAttributeSearchQueryTest {

    private static final String TEMPORARY_DATABASE = "product_attr_search_it";

    @Autowired
    private ProductRepository repository;

    @Autowired
    private FamilyRepository familyRepository;

    private Family family;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @BeforeEach
    void seedCatalogue() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        family = familyRepository.save(Family.builder()
                .code(code)
                .name("ESPECIAL " + code)
                .requiresModel(false)
                .category(FamilyCategory.SPECIAL)
                .attributes(new java.util.ArrayList<>())
                .build());
        family.addAttribute(Attribute.builder()
                .name("largo")
                .dataType(AttributeDataType.NUMBER)
                .isRequired(false)
                .build());
        family = familyRepository.save(family);
    }

    /**
     * Guarda el numero tal cual se le pase, que es justo lo que hace el
     * catalogo real: la misma medida esta como `15.0` en unas piezas y como
     * `200` en otras, segun por que camino se dieron de alta.
     */
    private Product product(String name, Map<String, Object> attributes) {
        return repository.save(Product.builder()
                .family(family)
                .name(name)
                .sku("SKU-" + UUID.randomUUID().toString().substring(0, 8))
                .attributes(new HashMap<>(attributes))
                .build());
    }

    private List<String> byNumber(String key, String value) {
        return repository.findAll(ProductSpecs.hasNumericAttribute(
                        key, new BigDecimal(value)))
                .stream()
                .map(Product::getName)
                .toList();
    }

    private List<String> byText(String key, Object value) {
        return repository.findAll(ProductSpecs.hasAttribute(key, value))
                .stream()
                .map(Product::getName)
                .toList();
    }

    // ── Numeros escritos de formas distintas ─────────────────────

    /**
     * El caso exacto que fallaba: la hoja se guardo con `15.0` y buscar 15 no la
     * encontraba, porque `jsonb_extract_path_text` devuelve la cadena "15.0" y
     * la comparacion era de texto.
     */
    @Test
    void aNumberIsFoundHoweverItWasSerialized() {
        product("HOJA 15 CON DECIMAL", Map.of("largo", new BigDecimal("15.0")));
        product("HOJA 200 ENTERA", Map.of("largo", 200));

        assertThat(byNumber("largo", "15")).containsExactly("HOJA 15 CON DECIMAL");
        assertThat(byNumber("largo", "15.0")).containsExactly("HOJA 15 CON DECIMAL");
        assertThat(byNumber("largo", "15.00")).containsExactly("HOJA 15 CON DECIMAL");
        assertThat(byNumber("largo", "200")).containsExactly("HOJA 200 ENTERA");
        assertThat(byNumber("largo", "200.0")).containsExactly("HOJA 200 ENTERA");
    }

    /** Comparar texto era justo lo que se rompia: se deja constancia del motivo. */
    @Test
    void theTextComparisonMissesTheSameNumber() {
        product("HOJA 15 CON DECIMAL", Map.of("largo", new BigDecimal("15.0")));

        assertThat(byText("largo", "15")).isEmpty();
        assertThat(byText("largo", "15.0")).containsExactly("HOJA 15 CON DECIMAL");
    }

    @Test
    void aNumberThatMatchesNothingReturnsNothing() {
        product("HOJA 15 CON DECIMAL", Map.of("largo", new BigDecimal("15.0")));

        assertThat(byNumber("largo", "16")).isEmpty();
    }

    /** Un producto sin esa caracteristica no estorba: no casa ni revienta. */
    @Test
    void productsWithoutTheAttributeAreIgnored() {
        product("HOJA 15", Map.of("largo", new BigDecimal("15.0")));
        product("SIN LARGO", Map.of("tieneOrificio", true));

        assertThat(byNumber("largo", "15")).containsExactly("HOJA 15");
    }

    /** Cero es un valor como otro cualquiera, no "sin valor". */
    @Test
    void zeroIsAValueLikeAnyOther() {
        product("COLOCHOS CERO", Map.of("cantidadColochos", 0));
        product("COLOCHOS DOS", Map.of("cantidadColochos", 2));

        assertThat(byNumber("cantidadColochos", "0")).containsExactly("COLOCHOS CERO");
    }

    // ── Texto y booleano no cambian ──────────────────────────────

    /**
     * Los que NO son numericos se siguen comparando como texto, que es lo
     * correcto: el JSONB los serializa siempre igual.
     */
    @Test
    void booleansAreStillComparedAsText() {
        product("CON ORIFICIO", Map.of("tieneOrificio", true));
        product("SIN ORIFICIO", Map.of("tieneOrificio", false));

        assertThat(byText("tieneOrificio", "true")).containsExactly("CON ORIFICIO");
        assertThat(byText("tieneOrificio", "false")).containsExactly("SIN ORIFICIO");
    }

    @Test
    void stringsAreStillComparedAsText() {
        product("DE ACERO", Map.of("material", "acero"));
        product("DE BRONCE", Map.of("material", "bronce"));

        assertThat(byText("material", "acero")).containsExactly("DE ACERO");
        assertThat(byText("material", "hierro")).isEmpty();
    }

    /** Varias caracteristicas a la vez se acumulan con AND. */
    @Test
    void severalAttributesNarrowTheSearch() {
        product("HOJA 15 CON ORIFICIO", Map.of("largo", new BigDecimal("15.0"), "tieneOrificio", true));
        product("HOJA 15 SIN ORIFICIO", Map.of("largo", 15, "tieneOrificio", false));

        List<String> found = repository.findAll(Specification.allOf(
                        ProductSpecs.hasNumericAttribute("largo", new BigDecimal("15")),
                        ProductSpecs.hasAttribute("tieneOrificio", "true")))
                .stream()
                .map(Product::getName)
                .toList();

        assertThat(found).containsExactly("HOJA 15 CON ORIFICIO");
    }

}
