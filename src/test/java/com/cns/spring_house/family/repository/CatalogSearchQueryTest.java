package com.cns.spring_house.family.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
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
import com.cns.spring_house.brand.repository.BrandSpecs;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.model.entity.Model;
import com.cns.spring_house.model.repository.ModelRepository;
import com.cns.spring_house.model.repository.ModelSpecs;

/**
 * Los tres buscadores que alimentan el alta de productos de la caja —familia,
 * marca y modelo— contra PostgreSQL de verdad.
 *
 * Un repositorio simulado no habria servido, por la MISMA razon que en producto
 * y en servicio: lo que fallaba era la comparacion que hace el motor. Familia y
 * modelo bajaban a minusculas la COLUMNA y dejaban el parametro tal cual, de
 * modo que `LOWER(name) LIKE '%Hoja%'` no casaba nunca. Escribir el nombre como
 * se escribe —con mayuscula inicial— devolvia cero resultados sobre un catalogo
 * que si tenia la familia.
 *
 * Marca ya trataba el caso, pero armaba el patron a mano: `%` y `_` seguian
 * siendo comodines y buscar "%" devolvia el catalogo entero.
 *
 * Usa una base temporal creada por la propia prueba; nunca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CatalogSearchQueryTest {

    private static final String TEMPORARY_DATABASE = "catalog_search_it";

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ModelRepository modelRepository;

    private Brand brand;

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
    void seedBrand() {
        brand = brandRepository.save(Brand.builder()
                .name("TOYOTA " + UUID.randomUUID().toString().substring(0, 8))
                .logoKey("logo")
                .build());
    }

    private Family family(String code, String name, FamilyCategory category) {
        return familyRepository.save(Family.builder()
                .code(code)
                .name(name)
                .requiresModel(category != FamilyCategory.JOB)
                .category(category)
                .build());
    }

    private Model model(String name) {
        return modelRepository.save(Model.builder()
                .brand(brand)
                .name(name)
                .yearFrom(2020)
                .yearTo(2025)
                .build());
    }

    /** Lo mismo que hace `FamilyServiceImpl.getAll`: un texto contra codigo O nombre. */
    private List<String> searchFamilies(String query) {
        return familyRepository.findAll(Specification.anyOf(
                        FamilySpecs.codeContains(query),
                        FamilySpecs.nameContains(query)))
                .stream()
                .map(Family::getName)
                .toList();
    }

    private List<String> searchModels(String query) {
        return modelRepository.findAll(Specification.allOf(
                        ModelSpecs.hasBrand(brand.getId()),
                        ModelSpecs.nameContains(query)))
                .stream()
                .map(Model::getName)
                .toList();
    }

    private List<String> searchBrands(String query) {
        return brandRepository.findAll(BrandSpecs.nameContains(query))
                .stream()
                .map(Brand::getName)
                .toList();
    }

    // ── Familia: mayusculas y minusculas ─────────────────────────
    //
    // Los nombres NO pueden ser los que siembra `V6__seed_default_families`
    // (MALETA, HOJA DE RESORTE, MATERIAL LISO): `family.name` es UNIQUE, asi que
    // reusarlos rompia el INSERT antes de llegar a comprobar nada. Se usan
    // nombres propios de la prueba, que ademas dejan la asercion exacta.

    /**
     * El caso exacto que fallaba: el catalogo guarda el nombre en mayusculas y
     * la persona escribe como quiere.
     */
    @Test
    void theSameFamilyIsFoundInAnyCase() {
        family("HR-01", "BALLESTA REFORZADA", FamilyCategory.SPECIAL);

        assertThat(searchFamilies("ballesta")).containsExactly("BALLESTA REFORZADA");
        assertThat(searchFamilies("BALLESTA")).containsExactly("BALLESTA REFORZADA");
        assertThat(searchFamilies("BaLlEsTa")).containsExactly("BALLESTA REFORZADA");
        assertThat(searchFamilies("Ballesta")).containsExactly("BALLESTA REFORZADA");
    }

    @Test
    void aFragmentMatchesAnywhereInTheFamilyName() {
        family("HR-02", "BALLESTA REFORZADA", FamilyCategory.SPECIAL);

        assertThat(searchFamilies("reforzada")).containsExactly("BALLESTA REFORZADA");
        assertThat(searchFamilies("TA REFOR")).containsExactly("BALLESTA REFORZADA");
    }

    /** El codigo se busca igual, y tambien sin distinguir mayusculas. */
    @Test
    void theFamilyCodeIsAlsoCaseInsensitive() {
        family("brz-03", "BUJE DE RESORTE", FamilyCategory.NORMAL);

        assertThat(searchFamilies("BRZ-03")).containsExactly("BUJE DE RESORTE");
        assertThat(searchFamilies("brz-03")).containsExactly("BUJE DE RESORTE");
    }

    @Test
    void aFamilyQueryThatMatchesNothingReturnsNothing() {
        family("HR-04", "BALLESTA REFORZADA", FamilyCategory.SPECIAL);

        assertThat(searchFamilies("amortiguador")).isEmpty();
        assertThat(searchFamilies("AMORTIGUADOR")).isEmpty();
    }

    /**
     * `%` es texto, no "todo lo que haya". Sin escapar, buscar "%" devolvia el
     * catalogo entero de familias —incluidas las sembradas por la migracion—,
     * que es lo contrario de buscar.
     */
    @Test
    void aPercentSignIsSearchedLiterallyInFamilies() {
        family("HR-05", "DESCUENTO 50% TALLER", FamilyCategory.NORMAL);
        family("HR-06", "BALLESTA REFORZADA", FamilyCategory.SPECIAL);

        assertThat(searchFamilies("50%")).containsExactly("DESCUENTO 50% TALLER");
        assertThat(searchFamilies("%")).containsExactly("DESCUENTO 50% TALLER");
    }

    /**
     * El filtro por categoria no lo toca este cambio, y es el que permite a la
     * caja dejar fuera los servicios del alta de productos. Ninguna familia
     * sembrada es JOB, asi que la unica que sale es la de la prueba.
     */
    @Test
    void theCategoryFilterStillSeparatesServicesFromProducts() {
        family("HR-07", "BALLESTA REFORZADA", FamilyCategory.SPECIAL);
        family("HR-08", "SERVICIOS DE TALLER", FamilyCategory.JOB);

        List<String> services = familyRepository
                .findAll(FamilySpecs.hasCategory(FamilyCategory.JOB))
                .stream()
                .map(Family::getName)
                .toList();

        assertThat(services).containsExactly("SERVICIOS DE TALLER");
    }

    // ── Modelo ───────────────────────────────────────────────────

    @Test
    void theSameModelIsFoundInAnyCase() {
        model("HILUX");

        assertThat(searchModels("hilux")).containsExactly("HILUX");
        assertThat(searchModels("HILUX")).containsExactly("HILUX");
        assertThat(searchModels("Hilux")).containsExactly("HILUX");
        assertThat(searchModels("HiLuX")).containsExactly("HILUX");
    }

    @Test
    void aPercentSignIsSearchedLiterallyInModels() {
        model("SERIE 50%");
        model("HILUX");

        assertThat(searchModels("%")).containsExactly("SERIE 50%");
    }

    /**
     * El intervalo de años sigue resolviendose igual: un año suelto encuentra al
     * modelo cuyo intervalo lo CUBRE. El cambio solo tocaba el nombre.
     */
    @Test
    void theYearRangeStillMatchesByCoverage() {
        model("HILUX");

        assertThat(modelRepository.findAll(Specification.allOf(
                        ModelSpecs.hasBrand(brand.getId()),
                        ModelSpecs.coversYear(2023))))
                .extracting(Model::getName)
                .containsExactly("HILUX");
        assertThat(modelRepository.findAll(Specification.allOf(
                        ModelSpecs.hasBrand(brand.getId()),
                        ModelSpecs.coversYear(2019))))
                .isEmpty();
    }

    // ── Marca ────────────────────────────────────────────────────

    @Test
    void theSameBrandIsFoundInAnyCase() {
        String name = brand.getName();

        assertThat(searchBrands("toyota")).contains(name);
        assertThat(searchBrands("TOYOTA")).contains(name);
        assertThat(searchBrands("Toyota")).contains(name);
    }

    @Test
    void aPercentSignIsSearchedLiterallyInBrands() {
        brandRepository.save(Brand.builder()
                .name("MARCA 50%")
                .logoKey("logo")
                .build());

        assertThat(searchBrands("%")).containsExactly("MARCA 50%");
    }

}
