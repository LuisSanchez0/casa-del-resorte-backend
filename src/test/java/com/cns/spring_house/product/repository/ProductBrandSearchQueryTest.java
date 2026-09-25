package com.cns.spring_house.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.brand.entity.Brand;
import com.cns.spring_house.brand.repository.BrandRepository;
import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.AttributeDataType;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.testsupport.TestDatabases;

/**
 * La MARCA como criterio propio de la busqueda de producto, contra PostgreSQL
 * de verdad.
 *
 * Hace falta la base real y no un repositorio simulado porque lo que se esta
 * comprobando es la forma de la consulta: la marca no cuelga del producto, se
 * alcanza por `product.model.brand`, y esa union es exactamente lo que puede
 * romperse. Una union mal puesta —permanente en lugar de condicional— no da
 * error: simplemente borra del catalogo a todas las piezas sin modelo en TODAS
 * las busquedas, incluidas las que no mencionan ninguna marca.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ProductBrandSearchQueryTest {

    private static final String TEMPORARY_DATABASE = "product_brand_search_it";

    @Autowired
    private ProductRepository repository;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ModelRepository modelRepository;

    private Family hoja;
    private Family universal;

    private Brand toyota;
    private Brand nissan;

    private Model corolla;
    private Model hilux;
    private Model frontier;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry) {
        String url = TestDatabases.provision(TEMPORARY_DATABASE);

        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", TestDatabases::username);
        registry.add("spring.datasource.password", TestDatabases::password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no se
        // acota se queda con las 10 conexiones por defecto de Hikari, y la suma
        // de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    @BeforeEach
    void seedCatalogue() {
        hoja = family("HOJA", true);
        universal = family("UNIVERSAL", false);

        toyota = brand("TOYOTA");
        nissan = brand("NISSAN");

        corolla = model(toyota, "COROLLA");
        hilux = model(toyota, "HILUX");
        frontier = model(nissan, "FRONTIER");
    }

    private Family family(String prefix, boolean withAttribute) {
        String code = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        Family created = familyRepository.save(Family.builder()
                .code(code)
                .name(prefix + " " + code)
                .requiresModel(true)
                .category(FamilyCategory.NORMAL)
                .attributes(new ArrayList<>())
                .build());

        if (!withAttribute) {
            return created;
        }

        created.addAttribute(Attribute.builder()
                .name("largo")
                .dataType(AttributeDataType.NUMBER)
                .isRequired(false)
                .build());
        created.addAttribute(Attribute.builder()
                .name("reforzada")
                .dataType(AttributeDataType.BOOLEAN)
                .isRequired(false)
                .build());
        return familyRepository.save(created);
    }

    private Brand brand(String name) {
        return brandRepository.save(Brand.builder()
                .name(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .logoKey("logos/" + name.toLowerCase() + ".png")
                .build());
    }

    private Model model(Brand brand, String name) {
        return modelRepository.save(Model.builder()
                .brand(brand)
                .name(name)
                .yearFrom(2020)
                .yearTo(2025)
                .build());
    }

    private Product product(
            Family family, Model model, String name, Map<String, Object> attributes) {
        return repository.save(Product.builder()
                .family(family)
                .model(model)
                .name(name)
                .sku("SKU-" + UUID.randomUUID().toString().substring(0, 8))
                .attributes(new HashMap<>(attributes))
                .build());
    }

    /** Los nombres que devuelve la busqueda, ordenados para poder compararlos. */
    private List<String> found(Specification<Product> spec) {
        return repository.findAll(spec)
                .stream()
                .map(Product::getName)
                .sorted()
                .toList();
    }

    /** La composicion REAL que arma el servicio, criterio a criterio. */
    private Specification<Product> search(
            String q, UUID family, UUID brand, UUID model) {
        List<Specification<Product>> specs = new ArrayList<>();
        specs.add(ProductSpecs.hasFamily(family));
        specs.add(ProductSpecs.hasBrand(brand));
        specs.add(ProductSpecs.hasModel(model));
        specs.add(Specification.anyOf(
                ProductSpecs.skuContains(q),
                ProductSpecs.nameContains(q)));
        return Specification.allOf(specs);
    }

    // ── Marca sola ───────────────────────────────────────────────

    /**
     * El caso que no existia: buscar TODO lo de una marca sin saber el modelo.
     * Devuelve las piezas de cualquiera de sus modelos, no solo las de uno.
     */
    @Test
    void aBrandAloneReturnsEveryProductOfItsModels() {
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));
        product(hoja, hilux, "HOJA HILUX", Map.of("largo", 20));
        product(hoja, frontier, "HOJA FRONTIER", Map.of("largo", 15));

        assertThat(found(search(null, null, toyota.getId(), null)))
                .containsExactly("HOJA COROLLA", "HOJA HILUX");
    }

    @Test
    void aBrandWithoutProductsReturnsNothing() {
        product(hoja, frontier, "HOJA FRONTIER", Map.of("largo", 15));

        assertThat(found(search(null, null, toyota.getId(), null))).isEmpty();
    }

    // ── Marca y modelo, juntos y por separado ────────────────────

    /** El modelo por si solo sigue funcionando igual que antes. */
    @Test
    void aModelAloneStillFiltersWithoutAnyBrand() {
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));
        product(hoja, hilux, "HOJA HILUX", Map.of("largo", 20));

        assertThat(found(search(null, null, null, corolla.getId())))
                .containsExactly("HOJA COROLLA");
    }

    @Test
    void brandAndModelNarrowTogether() {
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));
        product(hoja, hilux, "HOJA HILUX", Map.of("largo", 20));
        product(hoja, frontier, "HOJA FRONTIER", Map.of("largo", 15));

        assertThat(found(search(null, null, toyota.getId(), corolla.getId())))
                .containsExactly("HOJA COROLLA");
    }

    /**
     * Una combinacion imposible no es un error: sencillamente no hay ninguna
     * pieza que sea a la vez de Nissan y de un modelo de Toyota. La interfaz
     * evita llegar aqui, pero el servidor tiene que responder lo correcto.
     */
    @Test
    void aBrandThatDoesNotOwnTheModelReturnsNothing() {
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));

        assertThat(found(search(null, null, nissan.getId(), corolla.getId())))
                .isEmpty();
    }

    // ── Marca combinada con el resto de criterios ────────────────

    @Test
    void brandCombinesWithFamily() {
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));
        product(universal, hilux, "TORNILLO TOYOTA", Map.of());

        assertThat(found(search(null, hoja.getId(), toyota.getId(), null)))
                .containsExactly("HOJA COROLLA");
    }

    @Test
    void brandCombinesWithText() {
        product(hoja, corolla, "HOJA REFORZADA COROLLA", Map.of("largo", 15));
        product(hoja, hilux, "HOJA SENCILLA HILUX", Map.of("largo", 20));
        product(hoja, frontier, "HOJA REFORZADA FRONTIER", Map.of("largo", 15));

        assertThat(found(search("reforzada", null, toyota.getId(), null)))
                .containsExactly("HOJA REFORZADA COROLLA");
    }

    @Test
    void brandCombinesWithANumericAttribute() {
        product(hoja, corolla, "HOJA COROLLA 15", Map.of("largo", new BigDecimal("15.0")));
        product(hoja, hilux, "HOJA HILUX 20", Map.of("largo", 20));
        product(hoja, frontier, "HOJA FRONTIER 15", Map.of("largo", 15));

        Specification<Product> spec = Specification.allOf(
                ProductSpecs.hasBrand(toyota.getId()),
                ProductSpecs.hasNumericAttribute("largo", new BigDecimal("15")));

        assertThat(found(spec)).containsExactly("HOJA COROLLA 15");
    }

    @Test
    void brandCombinesWithABooleanAttribute() {
        product(hoja, corolla, "HOJA COROLLA REFORZADA",
                Map.of("largo", 15, "reforzada", true));
        product(hoja, hilux, "HOJA HILUX SENCILLA",
                Map.of("largo", 20, "reforzada", false));
        product(hoja, frontier, "HOJA FRONTIER REFORZADA",
                Map.of("largo", 15, "reforzada", true));

        Specification<Product> spec = Specification.allOf(
                ProductSpecs.hasBrand(toyota.getId()),
                ProductSpecs.hasAttribute("reforzada", true));

        assertThat(found(spec)).containsExactly("HOJA COROLLA REFORZADA");
    }

    @Test
    void familyBrandAndAttributeNarrowAllAtOnce() {
        product(hoja, corolla, "HOJA COROLLA 15", Map.of("largo", 15));
        product(hoja, hilux, "HOJA HILUX 15", Map.of("largo", 15));
        product(universal, corolla, "TORNILLO COROLLA", Map.of());

        Specification<Product> spec = Specification.allOf(
                ProductSpecs.hasFamily(hoja.getId()),
                ProductSpecs.hasBrand(toyota.getId()),
                ProductSpecs.hasModel(corolla.getId()),
                ProductSpecs.hasNumericAttribute("largo", new BigDecimal("15")));

        assertThat(found(spec)).containsExactly("HOJA COROLLA 15");
    }

    // ── Piezas sin modelo ────────────────────────────────────────

    /**
     * Lo que este filtro NO puede romper.
     *
     * Muchas piezas del catalogo no llevan modelo —su familia no lo exige—, y
     * son las que mas se buscan por texto. Si la union con el modelo fuera
     * permanente en lugar de condicional, desaparecerian de cualquier busqueda
     * sin que nada lo explicase.
     */
    @Test
    void productsWithoutAModelStayVisibleWhenNoBrandIsGiven() {
        product(universal, null, "TORNILLO UNIVERSAL", Map.of());
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));

        assertThat(found(search(null, null, null, null)))
                .containsExactly("HOJA COROLLA", "TORNILLO UNIVERSAL");
        assertThat(found(search("tornillo", null, null, null)))
                .containsExactly("TORNILLO UNIVERSAL");
        assertThat(found(search(null, universal.getId(), null, null)))
                .containsExactly("TORNILLO UNIVERSAL");
    }

    /** Y con marca puesta no aparecen, porque no pertenecen a ninguna. */
    @Test
    void productsWithoutAModelDoNotMatchABrand() {
        product(universal, null, "TORNILLO UNIVERSAL", Map.of());
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));

        assertThat(found(search(null, null, toyota.getId(), null)))
                .containsExactly("HOJA COROLLA");
    }

    // ── Sin marca no hay filtro ──────────────────────────────────

    @Test
    void aNullBrandDoesNotFilterAnything() {
        product(hoja, corolla, "HOJA COROLLA", Map.of("largo", 15));
        product(hoja, frontier, "HOJA FRONTIER", Map.of("largo", 15));
        product(universal, null, "TORNILLO UNIVERSAL", Map.of());

        assertThat(repository.findAll(ProductSpecs.hasBrand(null)))
                .hasSize(3);
    }
}
