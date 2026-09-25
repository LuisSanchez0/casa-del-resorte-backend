package com.cns.spring_house.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.cns.spring_house.testsupport.TestDatabases;

/**
 * El contrato de V40 frente a ventas que YA EXISTIAN.
 *
 * Antes de V40 la API solo creaba ventas con un Customer, que siempre tiene
 * NIT; una venta sin cliente solo podia llegar por fuera de la API y el cierre
 * la imprime como "Mostrador". La migracion tiene que traducir eso sin
 * inventar nada: con cliente es NIT, sin cliente es CF, y ninguna fila queda
 * sin definir —ese estado exige un nombre que ninguna tenia y jamas puede
 * aplicarse a una venta ya emitida—.
 *
 * JDBC a pelo y sin Spring, como las demas pruebas de migracion: lo que se
 * prueba es el SQL, aplicando V1..V39, sembrando a mano y aplicando V40.
 */
@TestInstance(Lifecycle.PER_CLASS)
class SaleTaxModeMigrationTest {

    private static final String DATABASE_PREFIX = "sale_tax_mode_migration_it_";

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final int TAX_MODE_VERSION = 40;

    private static final String WORKSHOP = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String EMPLOYEE = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String CUSTOMER = "aaaaaaaa-0000-0000-0000-000000000003";

    private static final String COMPLETED_WITH_NIT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PENDING_WITH_NIT = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String REVERSED_WITH_NIT = "bbbbbbbb-0000-0000-0000-000000000003";
    private static final String COMPLETED_COUNTER_SALE = "bbbbbbbb-0000-0000-0000-000000000004";
    private static final String PENDING_COUNTER_SALE = "bbbbbbbb-0000-0000-0000-000000000005";

    private Connection freshDatabase(String suffix) throws SQLException {
        String url = TestDatabases.provision(DATABASE_PREFIX + suffix);
        return DriverManager.getConnection(
                url, TestDatabases.username(), TestDatabases.password());
    }

    @Test
    void classifiesEveryHistoricalSaleWithoutLeavingAnyUnresolved() throws Exception {
        try (Connection connection = freshDatabase("legacy")) {

            applyMigrationsUpTo(connection, TAX_MODE_VERSION - 1);
            seedLegacySales(connection);
            int sales = count(connection, "SELECT COUNT(*) FROM sale");

            applyMigration(connection, TAX_MODE_VERSION);

            // Ni una fila menos.
            assertThat(count(connection, "SELECT COUNT(*) FROM sale")).isEqualTo(sales);

            // Con cliente -> NIT, con el mismo cliente y sin nombre suelto.
            for (String id : List.of(COMPLETED_WITH_NIT, PENDING_WITH_NIT, REVERSED_WITH_NIT)) {
                assertThat(text(connection, "SELECT tax_mode FROM sale WHERE id = '" + id + "'"))
                        .as("tax_mode de " + id).isEqualTo("NIT");
                assertThat(text(connection, "SELECT customer_id::text FROM sale WHERE id = '" + id + "'"))
                        .isEqualTo(CUSTOMER);
            }

            // Sin cliente -> CF (el "Mostrador" del cierre), nunca sin definir.
            for (String id : List.of(COMPLETED_COUNTER_SALE, PENDING_COUNTER_SALE)) {
                assertThat(text(connection, "SELECT tax_mode FROM sale WHERE id = '" + id + "'"))
                        .as("tax_mode de " + id).isEqualTo("CF");
            }

            assertThat(count(connection,
                    "SELECT COUNT(*) FROM sale WHERE tax_mode = 'UNRESOLVED'")).isZero();
            assertThat(count(connection,
                    "SELECT COUNT(*) FROM sale WHERE customer_name IS NOT NULL")).isZero();

            // El historial de cobro y estado queda intacto.
            assertThat(text(connection,
                    "SELECT status || '/' || payment_status || '/' || payment_method "
                    + "FROM sale WHERE id = '" + COMPLETED_WITH_NIT + "'"))
                    .isEqualTo("COMPLETED/PAID/CASH");
            assertThat(text(connection,
                    "SELECT status FROM sale WHERE id = '" + REVERSED_WITH_NIT + "'"))
                    .isEqualTo("REVERSED");

            // Y el NIT real sigue siendo unico y obligatorio.
            assertThat(insertSucceeds(connection, """
                    INSERT INTO customer (id, nit, name, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-0000000000c9', '12345678', 'OTRO',
                            now(), now())
                    """)).isFalse();
            assertThat(insertSucceeds(connection, """
                    INSERT INTO customer (id, nit, name, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-0000000000ca', NULL, 'SIN NIT',
                            now(), now())
                    """)).isFalse();
        }
    }

    /** El esquema nuevo acepta las tres formas validas y ninguna ambigua. */
    @Test
    void acceptsTheThreeValidShapesAndRejectsAmbiguousOnes() throws Exception {
        try (Connection connection = freshDatabase("shapes")) {

            applyMigrationsUpTo(connection, TAX_MODE_VERSION);
            seedParties(connection);

            assertThat(insertSale(connection, "c1", "'NIT'", "'" + CUSTOMER + "'", "NULL", "'PENDING'")).isTrue();
            assertThat(insertSale(connection, "c2", "'CF'", "NULL", "NULL", "'COMPLETED'")).isTrue();
            assertThat(insertSale(connection, "c3", "'CF'", "NULL", "'MARIA LOPEZ'", "'COMPLETED'")).isTrue();
            assertThat(insertSale(connection, "c4", "'UNRESOLVED'", "NULL", "'CARLOS'", "'PENDING'")).isTrue();
            assertThat(insertSale(connection, "c5", "'UNRESOLVED'", "NULL", "'CARLOS'", "'CANCELLED'")).isTrue();

            // Sin definir y ya emitida: nunca.
            assertThat(insertSale(connection, "d1", "'UNRESOLVED'", "NULL", "'CARLOS'", "'COMPLETED'")).isFalse();
            assertThat(insertSale(connection, "d2", "'UNRESOLVED'", "NULL", "'CARLOS'", "'REVERSED'")).isFalse();
            // Sin definir y sin nombre que la identifique.
            assertThat(insertSale(connection, "d3", "'UNRESOLVED'", "NULL", "NULL", "'PENDING'")).isFalse();
            // CF con un NIT colado.
            assertThat(insertSale(connection, "d4", "'CF'", "'" + CUSTOMER + "'", "NULL", "'COMPLETED'")).isFalse();
            // NIT sin cliente, o con un segundo nombre.
            assertThat(insertSale(connection, "d5", "'NIT'", "NULL", "NULL", "'PENDING'")).isFalse();
            assertThat(insertSale(connection, "d6", "'NIT'", "'" + CUSTOMER + "'", "'OTRO'", "'PENDING'")).isFalse();
            // Sin modo, o con uno que no existe.
            assertThat(insertSale(connection, "d7", "NULL", "'" + CUSTOMER + "'", "NULL", "'PENDING'")).isFalse();
            assertThat(insertSale(connection, "d8", "'PENDING'", "NULL", "'X'", "'PENDING'")).isFalse();
            // Un nombre en blanco no es un nombre.
            assertThat(insertSale(connection, "d9", "'CF'", "NULL", "'   '", "'COMPLETED'")).isFalse();
        }
    }

    // ── Datos de partida ─────────────────────────────────────────

    private void seedParties(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO workshop (id, name, address, is_active, created_at, updated_at)
                VALUES ('%s', 'TALLER', 'DIRECCION', true, now(), now())
                """.formatted(WORKSHOP));
            statement.execute("""
                INSERT INTO employee (id, workshop_id, first_name, last_name, dpi, birth_date,
                                      created_at, updated_at)
                VALUES ('%s', '%s', 'ANA', 'LOPEZ', '1234567890101', '1990-01-01', now(), now())
                """.formatted(EMPLOYEE, WORKSHOP));
            statement.execute("""
                INSERT INTO customer (id, nit, name, created_at, updated_at)
                VALUES ('%s', '12345678', 'JUAN PEREZ', now(), now())
                """.formatted(CUSTOMER));
        }
    }

    private void seedLegacySales(Connection connection) throws SQLException {
        seedParties(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO sale (id, workshop_id, employee_id, customer_id, status, completed_at,
                                  total, invoice_type, payment_status, payment_method, paid_at,
                                  created_at, updated_at)
                VALUES
                  ('%1$s', '%6$s', '%7$s', '%8$s', 'COMPLETED', now(), 100.00, 'INVOICE',
                   'PAID', 'CASH', now(), now(), now()),
                  ('%2$s', '%6$s', '%7$s', '%8$s', 'PENDING', NULL, 0, NULL,
                   'PENDING', NULL, NULL, now(), now()),
                  ('%3$s', '%6$s', '%7$s', '%8$s', 'REVERSED', now(), 50.00, 'INVOICE',
                   'PAID', 'CARD', now(), now(), now()),
                  ('%4$s', '%6$s', '%7$s', NULL, 'COMPLETED', now(), 75.00, 'INVOICE',
                   'PAID', 'CASH', now(), now(), now()),
                  ('%5$s', '%6$s', '%7$s', NULL, 'PENDING', NULL, 0, NULL,
                   'PENDING', NULL, NULL, now(), now())
                """.formatted(COMPLETED_WITH_NIT, PENDING_WITH_NIT, REVERSED_WITH_NIT,
                        COMPLETED_COUNTER_SALE, PENDING_COUNTER_SALE,
                        WORKSHOP, EMPLOYEE, CUSTOMER));
        }
    }

    /**
     * Inserta una venta con la identidad indicada. Los argumentos ya vienen
     * como literales SQL (`'NIT'`, `NULL`) para poder probar tambien el NULL.
     */
    private boolean insertSale(
            Connection connection, String suffix, String taxMode,
            String customerId, String customerName, String status) {
        boolean completed = status.contains("COMPLETED") || status.contains("REVERSED");
        String id = "cccccccc-0000-0000-0000-0000000000" + suffix;
        return insertSucceeds(connection, """
                INSERT INTO sale (id, workshop_id, employee_id, customer_id, status,
                                  completed_at, total, invoice_type, payment_status,
                                  payment_method, paid_at, tax_mode, customer_name,
                                  created_at, updated_at)
                VALUES ('%s', '%s', '%s', %s, %s, %s, 10.00, %s, %s, %s, %s, %s, %s,
                        now(), now())
                """.formatted(id, WORKSHOP, EMPLOYEE, customerId, status,
                        completed ? "now()" : "NULL",
                        completed ? "'INVOICE'" : "NULL",
                        completed ? "'PAID'" : "'PENDING'",
                        completed ? "'CASH'" : "NULL",
                        completed ? "now()" : "NULL",
                        taxMode, customerName));
    }

    private boolean insertSucceeds(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return true;
        } catch (SQLException rejected) {
            return false;
        }
    }

    // ── Aplicacion de migraciones ────────────────────────────────

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

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
            scripts.sort(Comparator.comparingInt(SaleTaxModeMigrationTest::versionOf));
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

    private int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

}
