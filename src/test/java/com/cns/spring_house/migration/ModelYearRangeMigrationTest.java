package com.cns.spring_house.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * El contrato de la migracion V35 frente a un catalogo que YA EXISTIA.
 *
 * `model.year` guardaba un unico año, asi que toda fila anterior vale para
 * exactamente ese año y para ninguno mas. Traducida al intervalo nuevo, esa
 * misma afirmacion es [year, year]: los dos extremos iguales. No hay ninguna
 * fila que interpretar de otra manera, de modo que la conversion es total y no
 * pierde ni un modelo.
 *
 * Importa mas de lo que parece porque `product.model_id` apunta a estas filas:
 * un catalogo que se reiniciara dejaria sin modelo a los productos que ya
 * existen, y con ellos a su inventario y a su historial de ventas.
 *
 * Se ejecuta a pelo con JDBC y sin contexto de Spring porque lo que se prueba
 * es el SQL: aplicar V1..V34, sembrar a mano, aplicar V35 y comparar. Con
 * Flyway ya ejecutado por el arranque de la aplicacion no se podria observar el
 * ANTES.
 */
@TestInstance(Lifecycle.PER_CLASS)
class ModelYearRangeMigrationTest {

    /** Una base POR PRUEBA: compartirla ataria el resultado al orden de JUnit. */
    private static final String DATABASE_PREFIX = "model_year_range_migration_it_";

    private static final Path MIGRATIONS =
            Path.of("src/main/resources/db/migration");

    /** Version que sustituye `year` por el intervalo. */
    private static final int YEAR_RANGE_VERSION = 35;

    private static final String BRAND = "aaaaaaaa-0000-0000-0000-0000000000b1";

    /** Modelos historicos: nombre -> año unico que tenian antes. */
    private static final Map<String, Integer> LEGACY_MODELS = new LinkedHashMap<>();

    static {
        LEGACY_MODELS.put("Corolla", 1900);
        LEGACY_MODELS.put("Hilux", 2020);
        LEGACY_MODELS.put("Civic", 2024);
    }

    private String username;
    private String password;
    private String server;

    @BeforeAll
    void readConnectionSettings() throws Exception {
        Properties environment = loadEnvironment();
        String host = value(environment, "DB_HOST", "localhost");
        String port = value(environment, "DB_PORT", "5432");
        username = value(environment, "DB_USER", "postgres");
        password = value(environment, "DB_PASS", null);
        server = "jdbc:postgresql://" + host + ":" + port + "/";
    }

    private Connection freshDatabase(String suffix) throws SQLException {
        String database = DATABASE_PREFIX + suffix;
        try (Connection admin = DriverManager.getConnection(
                server + "postgres", username, password);
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database
                    + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + database);
        }
        return DriverManager.getConnection(server + database, username, password);
    }

    /**
     * Todo el ciclo en una sola prueba porque es UNA historia: el catalogo
     * antiguo, la migracion y el intervalo resultante.
     */
    @Test
    void turnsEveryLegacyYearIntoASingleYearRangeWithoutLosingRows() throws Exception {
        try (Connection connection = freshDatabase("legacy")) {

            applyMigrationsUpTo(connection, YEAR_RANGE_VERSION - 1);
            seedLegacyCatalog(connection);

            // ── ANTES: modelos con año unico, y productos colgando de ellos ──
            int models = count(connection, "model");
            int products = count(connection, "product");

            assertThat(models).isEqualTo(LEGACY_MODELS.size());
            assertThat(products).isPositive();

            applyMigration(connection, YEAR_RANGE_VERSION);

            // ── DESPUES: ni una fila menos ────────────────────────────────
            assertThat(count(connection, "model")).isEqualTo(models);
            assertThat(count(connection, "product")).isEqualTo(products);

            // ── Y cada año viejo es ahora un intervalo de un solo año ─────
            for (Map.Entry<String, Integer> legacy : LEGACY_MODELS.entrySet()) {
                assertThat(scalar(connection,
                        "SELECT year_from FROM model WHERE name = '"
                        + legacy.getKey() + "'"))
                        .as("year_from de " + legacy.getKey())
                        .isEqualTo(legacy.getValue());
                assertThat(scalar(connection,
                        "SELECT year_to FROM model WHERE name = '"
                        + legacy.getKey() + "'"))
                        .as("year_to de " + legacy.getKey())
                        .isEqualTo(legacy.getValue());
            }

            // Ninguna fila queda a medias: la columna vieja ya no existe y las
            // nuevas son obligatorias.
            assertThat(columnsOf(connection, "model"))
                    .contains("year_from", "year_to")
                    .doesNotContain("year");
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM model
                    WHERE year_from IS NULL OR year_to IS NULL
                    """)).isZero();

            // El producto sigue apuntando a SU modelo, no a otro ni a ninguno.
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM product p
                    JOIN model m ON m.id = p.model_id
                    WHERE m.name = 'Hilux'
                    """)).isEqualTo(1);
        }
    }

    /**
     * El esquema resultante es USABLE, no solo compatible: acepta el intervalo
     * de varios años que antes no cabia, y rechaza el que esta al reves.
     */
    @Test
    void acceptsRealRangesAndRejectsAnInvertedOne() throws Exception {
        try (Connection connection = freshDatabase("usable")) {

            applyMigrationsUpTo(connection, YEAR_RANGE_VERSION);
            seedBrand(connection);

            insertModel(connection, "aaaaaaaa-0000-0000-0000-0000000000c1",
                    "Corolla", 2020, 2025);
            insertModel(connection, "aaaaaaaa-0000-0000-0000-0000000000c2",
                    "Mazda 3", 2022, 2022);

            assertThat(count(connection, "model")).isEqualTo(2);

            // El invariante lo sostiene la propia tabla, no solo el servicio.
            assertThatInsertFails(connection, "aaaaaaaa-0000-0000-0000-0000000000c3",
                    "Invertido", 2025, 2020);

            // Y los dos extremos siguen acotados como en V5.
            assertThatInsertFails(connection, "aaaaaaaa-0000-0000-0000-0000000000c4",
                    "Antiguo", 1899, 1899);
        }
    }

    /**
     * La unicidad del catalogo sobrevive al cambio de columnas: mismo nombre y
     * mismo intervalo para la misma marca sigue siendo el mismo modelo.
     */
    @Test
    void keepsTheCatalogUniqueByBrandNameAndRange() throws Exception {
        try (Connection connection = freshDatabase("unique")) {

            applyMigrationsUpTo(connection, YEAR_RANGE_VERSION);
            seedBrand(connection);

            insertModel(connection, "aaaaaaaa-0000-0000-0000-0000000000d1",
                    "Corolla", 2020, 2025);

            assertThatInsertFails(connection, "aaaaaaaa-0000-0000-0000-0000000000d2",
                    "Corolla", 2020, 2025);

            // Un intervalo distinto para el mismo nombre SI cabe: el catalogo
            // distingue generaciones, no las funde.
            insertModel(connection, "aaaaaaaa-0000-0000-0000-0000000000d3",
                    "Corolla", 2020, 2024);

            assertThat(count(connection, "model")).isEqualTo(2);
        }
    }

    // ── Datos de partida ─────────────────────────────────────────

    private void seedBrand(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO brand (id, name, logo_key, created_at, updated_at)
                VALUES ('%s', 'TOYOTA', 'logo.png', now(), now())
                """.formatted(BRAND));
        }
    }

    /** Marca, modelos con `year` a la antigua y un producto que los usa. */
    private void seedLegacyCatalog(Connection connection) throws SQLException {
        seedBrand(connection);
        try (Statement statement = connection.createStatement()) {
            int index = 0;
            for (Map.Entry<String, Integer> legacy : LEGACY_MODELS.entrySet()) {
                statement.execute("""
                    INSERT INTO model (id, brand_id, name, year, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-00000000e00%d', '%s', '%s', %d, now(), now())
                    """.formatted(index, BRAND, legacy.getKey(), legacy.getValue()));
                index++;
            }
            statement.execute("""
                INSERT INTO family (id, code, name, requires_model, category, created_at, updated_at)
                VALUES ('aaaaaaaa-0000-0000-0000-0000000000f1', 'HOJA-MIG', 'HOJA MIGRACION',
                        true, 'NORMAL', now(), now())
                """);
            statement.execute("""
                INSERT INTO product (id, sku, family_id, name, attributes, model_id, created_at, updated_at)
                VALUES ('aaaaaaaa-0000-0000-0000-0000000000f2', 'SKU-MIG',
                        'aaaaaaaa-0000-0000-0000-0000000000f1', 'HOJA HILUX', '{}'::jsonb,
                        'aaaaaaaa-0000-0000-0000-00000000e001', now(), now())
                """);
        }
    }

    private void insertModel(
            Connection connection, String id, String name, int from, int to)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO model (id, brand_id, name, year_from, year_to, created_at, updated_at)
                VALUES ('%s', '%s', '%s', %d, %d, now(), now())
                """.formatted(id, BRAND, name, from, to));
        }
    }

    private void assertThatInsertFails(
            Connection connection, String id, String name, int from, int to) {
        try {
            insertModel(connection, id, name, from, to);
            throw new AssertionError(
                    "La base acepto un modelo que no deberia: " + name
                    + " " + from + "-" + to);
        } catch (SQLException expected) {
            assertThat(expected.getMessage()).isNotBlank();
        }
    }

    // ── Aplicacion de migraciones ────────────────────────────────

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * Aplica las migraciones en ORDEN DE VERSION NUMERICA, no alfabetico:
     * alfabeticamente `V10` va antes que `V2` y la primera tabla no existiria.
     */
    private void applyMigrationsUpTo(Connection connection, int maxVersion)
            throws IOException, SQLException {
        for (Path script : migrationsInOrder()) {
            if (versionOf(script) <= maxVersion) {
                execute(connection, script);
            }
        }
    }

    private void applyMigration(Connection connection, int version)
            throws IOException, SQLException {
        for (Path script : migrationsInOrder()) {
            if (versionOf(script) == version) {
                execute(connection, script);
                return;
            }
        }
        throw new IllegalStateException("No existe la migracion V" + version);
    }

    private List<Path> migrationsInOrder() throws IOException {
        try (var files = Files.list(MIGRATIONS)) {
            List<Path> scripts = new ArrayList<>(files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList());
            scripts.sort(Comparator.comparingInt(ModelYearRangeMigrationTest::versionOf));
            return scripts;
        }
    }

    private static int versionOf(Path script) {
        Matcher matcher = VERSION.matcher(script.getFileName().toString());
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Nombre de migracion inesperado: " + script.getFileName());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private void execute(Connection connection, Path script)
            throws IOException, SQLException {
        String sql = Files.readString(script, StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ── Lecturas ─────────────────────────────────────────────────

    private List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = '" + table + "'")) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        return columns;
    }

    private int count(Connection connection, String table) throws SQLException {
        return scalar(connection, "SELECT COUNT(*) FROM " + table);
    }

    private int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
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

}
