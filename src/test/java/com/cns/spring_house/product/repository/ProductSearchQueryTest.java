package com.cns.spring_house.product.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
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

import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.family.repository.FamilyRepository;
import com.cns.spring_house.product.entity.Product;

/**
 * La busqueda de producto contra PostgreSQL de verdad.
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
class ProductSearchQueryTest {

    private static final String TEMPORARY_DATABASE = "product_search_it";

    @Autowired
    private ProductRepository repository;

    @Autowired
    private FamilyRepository familyRepository;

    private Family family;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry)
            throws IOException, SQLException {
        Properties environment = loadEnvironment();
        String host = value(environment, "DB_HOST", "localhost");
        String port = value(environment, "DB_PORT", "5432");
        String username = value(environment, "DB_USER", "postgres");
        String password = value(environment, "DB_PASS", null);
        String server = "jdbc:postgresql://" + host + ":" + port + "/";

        try (Connection connection = DriverManager.getConnection(
                server + "postgres", username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE
                    + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        registry.add("spring.datasource.url", () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    private static Properties loadEnvironment() throws IOException {
        Properties environment = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try (InputStream input = Files.newInputStream(envFile)) {
                environment.load(input);
            }
        }
        return environment;
    }

    private static String value(
            Properties environment, String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return environment.getProperty(key, fallback);
    }

    @BeforeEach
    void seedCatalogue() {
        String code = UUID.randomUUID().toString().substring(0, 8);
        family = familyRepository.save(Family.builder()
                .code(code)
                .name("ESPECIAL " + code)
                .requiresModel(false)
                .category(FamilyCategory.SPECIAL)
                .build());
    }

    private Product product(String name, String sku) {
        return repository.save(Product.builder()
                .family(family)
                .name(name)
                .sku(sku)
                .attributes(new HashMap<>())
                .build());
    }

    /** Lo que hace el buscador real: un texto contra nombre O sku. */
    private List<String> search(String query) {
        return repository.findAll(Specification.anyOf(
                        ProductSpecs.skuContains(query),
                        ProductSpecs.nameContains(query)))
                .stream()
                .map(Product::getName)
                .toList();
    }

    // ── Mayusculas y minusculas ──────────────────────────────────

    /**
     * El caso exacto que fallaba: el catalogo guarda los nombres en mayusculas y
     * la persona escribe como quiere.
     */
    @Test
    void theSameProductIsFoundInAnyCase() {
        product("MUELLE RECTIFICADO 60X10", "SKU-MUELLE-1");

        assertThat(search("muelle")).containsExactly("MUELLE RECTIFICADO 60X10");
        assertThat(search("MUELLE")).containsExactly("MUELLE RECTIFICADO 60X10");
        assertThat(search("MuElLe")).containsExactly("MUELLE RECTIFICADO 60X10");
        assertThat(search("Muelle")).containsExactly("MUELLE RECTIFICADO 60X10");
    }

    /** Y en cualquier posicion, no solo al principio. */
    @Test
    void aFragmentMatchesAnywhereInTheName() {
        product("MUELLE RECTIFICADO 60X10", "SKU-MUELLE-2");

        assertThat(search("RECTIFICADO")).containsExactly("MUELLE RECTIFICADO 60X10");
        assertThat(search("rectificado")).containsExactly("MUELLE RECTIFICADO 60X10");
        assertThat(search("rectificado 60")).containsExactly("MUELLE RECTIFICADO 60X10");
        assertThat(search("60X10")).containsExactly("MUELLE RECTIFICADO 60X10");
    }

    /** El sku sigue buscandose igual, y tambien sin distinguir mayusculas. */
    @Test
    void theSkuIsAlsoCaseInsensitive() {
        product("HOJA DE RESORTE", "sku-hoja-9");

        assertThat(search("SKU-HOJA-9")).containsExactly("HOJA DE RESORTE");
        assertThat(search("sku-hoja-9")).containsExactly("HOJA DE RESORTE");
    }

    @Test
    void aQueryThatMatchesNothingReturnsNothing() {
        product("MUELLE RECTIFICADO 60X10", "SKU-MUELLE-3");

        assertThat(search("amortiguador")).isEmpty();
        assertThat(search("AMORTIGUADOR")).isEmpty();
    }

    // ── Comodines literales ──────────────────────────────────────

    /**
     * `%` es texto, no "todo lo que haya".
     *
     * Sin escapar, buscar "%" devolvia el catalogo entero, que es justo lo
     * contrario de buscar.
     */
    @Test
    void aPercentSignIsSearchedLiterally() {
        product("DESCUENTO 50% TALLER", "SKU-PCT-1");
        product("MUELLE RECTIFICADO 60X10", "SKU-PCT-2");

        assertThat(search("50%")).containsExactly("DESCUENTO 50% TALLER");
        assertThat(search("%")).containsExactly("DESCUENTO 50% TALLER");
    }

    /** `_` es un guion bajo, no "un caracter cualquiera". */
    @Test
    void anUnderscoreIsSearchedLiterally() {
        product("LOTE_2026", "SKU-UND-1");
        product("LOTEX2026", "SKU-UND-2");

        assertThat(search("lote_2026")).containsExactly("LOTE_2026");
    }

    /** Y la propia barra de escape tambien es texto. */
    @Test
    void aBackslashIsSearchedLiterally() {
        product("MEDIDA 1\\2 PULGADA", "SKU-BS-1");
        product("MUELLE RECTIFICADO 60X10", "SKU-BS-2");

        assertThat(search("1\\2")).containsExactly("MEDIDA 1\\2 PULGADA");
    }

}
