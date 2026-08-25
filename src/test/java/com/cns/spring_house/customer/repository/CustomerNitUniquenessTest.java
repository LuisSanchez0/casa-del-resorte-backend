package com.cns.spring_house.customer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Garantia de unicidad del NIT contra PostgreSQL de verdad, sobre una base
 * temporal migrada por Flyway que la propia prueba crea y destruye. Nunca toca
 * la base de desarrollo.
 *
 * `existsByNit` en el servicio da el mensaje amable, pero es una comprobacion
 * previa: entre leerla y grabar caben dos peticiones simultaneas. Quien impide
 * el duplicado de verdad es la restriccion de la columna, y eso es lo que se
 * comprueba aqui.
 *
 * Deliberadamente NO usa `@DataJpaTest`: cada contexto de Spring cacheado
 * mantiene su propio pool abierto hasta el final de la suite, y con los que ya
 * existen el conjunto roza `max_connections`. Anadir uno mas hacia fallar a la
 * siguiente prueba de base de datos con "sorry, too many clients already". Con
 * JDBC directo esta clase gasta una sola conexion y la cierra.
 */
class CustomerNitUniquenessTest {

    private static final String TEMPORARY_DATABASE = "customer_nit_unique_it";

    private static String server;
    private static String username;
    private static String password;

    @BeforeAll
    static void createTemporaryDatabase() throws IOException, SQLException {
        Properties environment = loadEnvironment();
        String host = value(environment, "DB_HOST", "localhost");
        String port = value(environment, "DB_PORT", "5432");
        username = value(environment, "DB_USER", "postgres");
        password = value(environment, "DB_PASS", null);
        server = "jdbc:postgresql://" + host + ":" + port + "/";

        try (Connection connection = admin();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        // El esquema lo pone Flyway, que es la fuente autoritativa: asi la prueba
        // comprueba la restriccion que realmente se despliega, no una inventada.
        Flyway.configure()
                .dataSource(server + TEMPORARY_DATABASE, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void dropTemporaryDatabase() throws SQLException {
        try (Connection connection = admin();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE + " WITH (FORCE)");
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(server + "postgres", username, password);
    }

    private static Connection database() throws SQLException {
        return DriverManager.getConnection(
                server + TEMPORARY_DATABASE, username, password);
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

    private void insertCustomer(Connection connection, String nit, String name)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO customer (id, nit, name, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', now(), now())
                    """.formatted(UUID.randomUUID(), nit, name));
        }
    }

    @Test
    void aCustomerWithAFreeNitIsStoredNormally() throws SQLException {
        try (Connection connection = database()) {
            insertCustomer(connection, "80000001", "CLIENTE UNO");

            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT count(*) FROM customer WHERE nit = '80000001'")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void twoCustomersCanCoexistWithDifferentNits() throws SQLException {
        try (Connection connection = database()) {
            insertCustomer(connection, "80000002", "CLIENTE DOS");
            insertCustomer(connection, "80000003", "CLIENTE TRES");

            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT count(*) FROM customer WHERE nit IN ('80000002','80000003')")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(2);
            }
        }
    }

    /**
     * El escenario que `existsByNit` no puede cubrir por si solo: esta insercion
     * no pasa por el servicio y aun asi la base la rechaza.
     */
    @Test
    void theDatabaseRejectsASecondCustomerWithTheSameNit() throws SQLException {
        try (Connection connection = database()) {
            insertCustomer(connection, "80000004", "CLIENTE ORIGINAL");

            assertThatThrownBy(
                    () -> insertCustomer(connection, "80000004", "CLIENTE DUPLICADO"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(thrown -> assertThat(
                            ((SQLException) thrown).getSQLState()).isEqualTo("23505"))
                    .hasMessageContaining("customer_nit_key");
        }
    }

    /** La restriccion la crea la migracion V11, no `ddl-auto`. */
    @Test
    void theSchemaDeclaresAUniqueConstraintOnNit() throws SQLException {
        try (Connection connection = database();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT con.conname, pg_get_constraintdef(con.oid)
                        FROM pg_constraint con
                        JOIN pg_class rel ON rel.oid = con.conrelid
                        WHERE rel.relname = 'customer' AND con.contype = 'u'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("customer_nit_key");
            assertThat(result.getString(2)).isEqualTo("UNIQUE (nit)");
            assertThat(result.next())
                    .as("no debe haber una segunda restriccion unica duplicada")
                    .isFalse();
        }
    }

    @Test
    void theUniqueConstraintIsBackedByAUniqueIndex() throws SQLException {
        try (Connection connection = database();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT indexdef FROM pg_indexes
                        WHERE tablename = 'customer' AND indexname = 'customer_nit_key'
                        """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1))
                    .contains("CREATE UNIQUE INDEX")
                    .contains("(nit)");
        }
    }
}
