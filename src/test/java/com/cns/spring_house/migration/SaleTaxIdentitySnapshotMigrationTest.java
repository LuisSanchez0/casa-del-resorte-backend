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
 * El contrato de V41 frente a ventas que ya existian con V40.
 *
 * V41 no inventa historia: una venta con NIT ya cerrada recibe el cliente TAL
 * COMO ESTA al migrar (el mejor esfuerzo posible, porque nunca se guardo como
 * era al emitir), un CF emitido sigue siendo CF sin NIT, un CF que seguia
 * abierto —solo pudo ser un carrito abandonado— vuelve a estar sin definir, y
 * ninguna venta emitida queda sin definir.
 *
 * JDBC a pelo y sin Spring, como las demas pruebas de migracion.
 */
@TestInstance(Lifecycle.PER_CLASS)
class SaleTaxIdentitySnapshotMigrationTest {

    private static final String DATABASE_PREFIX = "sale_tax_identity_migration_it_";

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final int SNAPSHOT_VERSION = 41;

    private static final String WORKSHOP = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String EMPLOYEE = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String CUSTOMER = "aaaaaaaa-0000-0000-0000-000000000003";

    private static final String NIT_COMPLETED = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String NIT_REVERSED = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String NIT_CANCELLED = "bbbbbbbb-0000-0000-0000-000000000003";
    private static final String NIT_PENDING = "bbbbbbbb-0000-0000-0000-000000000004";
    private static final String CF_COMPLETED = "bbbbbbbb-0000-0000-0000-000000000005";
    private static final String CF_PENDING = "bbbbbbbb-0000-0000-0000-000000000006";
    private static final String UNRESOLVED_PENDING = "bbbbbbbb-0000-0000-0000-000000000007";
    private static final String UNRESOLVED_CANCELLED = "bbbbbbbb-0000-0000-0000-000000000008";

    private Connection freshDatabase(String suffix) throws SQLException {
        String url = TestDatabases.provision(DATABASE_PREFIX + suffix);
        return DriverManager.getConnection(
                url, TestDatabases.username(), TestDatabases.password());
    }

    @Test
    void migratesEveryV40ShapeWithoutInventingHistory() throws Exception {
        try (Connection connection = freshDatabase("legacy")) {

            applyMigrationsUpTo(connection, SNAPSHOT_VERSION - 1);
            seedV40Sales(connection);
            int sales = count(connection, "SELECT COUNT(*) FROM sale");

            applyMigration(connection, SNAPSHOT_VERSION);

            assertThat(count(connection, "SELECT COUNT(*) FROM sale")).isEqualTo(sales);

            // B. NIT cerrada -> congelada con el cliente tal como esta al migrar.
            for (String id : List.of(NIT_COMPLETED, NIT_REVERSED, NIT_CANCELLED)) {
                assertThat(sale(connection, id)).as(id).isEqualTo("NIT|JUAN PEREZ|12345678|f");
            }
            // NIT abierta: sigue viva y retomable, como lo era antes.
            assertThat(sale(connection, NIT_PENDING)).isEqualTo("NIT|null|null|t");

            // C. CF emitido sigue siendo CF, sin NIT inventado.
            assertThat(sale(connection, CF_COMPLETED)).isEqualTo("CF|MARIA LOPEZ|null|f");
            // Un CF abierto solo pudo ser un carrito abandonado: sin definir y
            // sin guardar, conservando el nombre que tuviera.
            assertThat(sale(connection, CF_PENDING)).isEqualTo("UNRESOLVED|ANA|null|f");

            // D. Sin definir sigue sin definir; retomable si seguia abierta.
            assertThat(sale(connection, UNRESOLVED_PENDING)).isEqualTo("UNRESOLVED|CARLOS GOMEZ|null|t");
            assertThat(sale(connection, UNRESOLVED_CANCELLED)).isEqualTo("UNRESOLVED|PEDRO|null|f");

            // E. Ninguna emitida sin definir, ningun CF abierto, ningun "CF" como NIT.
            assertThat(count(connection,
                    "SELECT COUNT(*) FROM sale "
                    + "WHERE (tax_mode = 'UNRESOLVED' AND status IN ('COMPLETED', 'REVERSED')) "
                    + "OR (tax_mode = 'CF' AND status = 'PENDING') "
                    + "OR customer_nit = 'CF'")).isZero();

            // El historial de cobro queda intacto.
            assertThat(text(connection,
                    "SELECT status || '/' || payment_status || '/' || payment_method "
                    + "FROM sale WHERE id = '" + NIT_COMPLETED + "'"))
                    .isEqualTo("COMPLETED/PAID/CASH");

            // G. El NIT real sigue siendo unico y obligatorio.
            assertThat(succeeds(connection,
                    "INSERT INTO customer (id, nit, name, created_at, updated_at) VALUES "
                    + "('aaaaaaaa-0000-0000-0000-0000000000c9', '12345678', 'OTRO', now(), now())"))
                    .isFalse();
            assertThat(succeeds(connection,
                    "INSERT INTO customer (id, nit, name, created_at, updated_at) VALUES "
                    + "('aaaaaaaa-0000-0000-0000-0000000000ca', NULL, 'SIN NIT', now(), now())"))
                    .isFalse();
        }
    }

    /** F. El esquema final acepta las formas validas y ninguna ambigua. */
    @Test
    void acceptsTheValidShapesAndRejectsAmbiguousOnes() throws Exception {
        try (Connection connection = freshDatabase("shapes")) {

            applyMigrationsUpTo(connection, SNAPSHOT_VERSION);
            seedParties(connection);
            String customer = "'" + CUSTOMER + "'";

            // Validas.
            assertThat(insert(connection, "c1", "'NIT'", customer, "NULL", "NULL", "'PENDING'", false)).isTrue();
            assertThat(insert(connection, "c2", "'NIT'", customer, "'JUAN PEREZ'", "'12345678'", "'COMPLETED'", false)).isTrue();
            assertThat(insert(connection, "c3", "'CF'", "NULL", "NULL", "NULL", "'COMPLETED'", false)).isTrue();
            assertThat(insert(connection, "c4", "'CF'", "NULL", "'MARIA'", "NULL", "'REVERSED'", false)).isTrue();
            assertThat(insert(connection, "c5", "'UNRESOLVED'", "NULL", "NULL", "NULL", "'PENDING'", false)).isTrue();
            assertThat(insert(connection, "c6", "'UNRESOLVED'", "NULL", "'CARLOS'", "NULL", "'PENDING'", true)).isTrue();

            // CF abierto: nunca, ni guardado ni sin guardar.
            assertThat(insert(connection, "d1", "'CF'", "NULL", "NULL", "NULL", "'PENDING'", false)).isFalse();
            assertThat(insert(connection, "d2", "'CF'", "NULL", "'MARIA'", "NULL", "'PENDING'", true)).isFalse();
            // CF con un NIT colado o con cliente registrado.
            assertThat(insert(connection, "d3", "'CF'", "NULL", "NULL", "'12345678'", "'COMPLETED'", false)).isFalse();
            assertThat(insert(connection, "d4", "'CF'", customer, "NULL", "NULL", "'COMPLETED'", false)).isFalse();
            // NIT cerrada sin su identidad congelada; NIT abierta ya congelada.
            assertThat(insert(connection, "d5", "'NIT'", customer, "NULL", "NULL", "'COMPLETED'", false)).isFalse();
            assertThat(insert(connection, "d6", "'NIT'", customer, "'JUAN'", "NULL", "'COMPLETED'", false)).isFalse();
            assertThat(insert(connection, "d7", "'NIT'", customer, "'JUAN'", "'12345678'", "'PENDING'", false)).isFalse();
            // Sin definir: emitida, o guardada sin nombre.
            assertThat(insert(connection, "d8", "'UNRESOLVED'", "NULL", "'CARLOS'", "NULL", "'COMPLETED'", false)).isFalse();
            assertThat(insert(connection, "d9", "'UNRESOLVED'", "NULL", "NULL", "NULL", "'PENDING'", true)).isFalse();
            // Un NIT congelado fuera de la longitud de la columna de cliente.
            assertThat(insert(connection, "da", "'NIT'", customer, "'JUAN'", "'123'", "'COMPLETED'", false)).isFalse();
        }
    }

    // ── Datos de partida ─────────────────────────────────────────

    private void seedParties(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO workshop (id, name, address, is_active, created_at, updated_at) "
                    + "VALUES ('" + WORKSHOP + "', 'TALLER', 'DIRECCION', true, now(), now())");
            statement.execute("INSERT INTO employee (id, workshop_id, first_name, last_name, dpi, "
                    + "birth_date, created_at, updated_at) VALUES ('" + EMPLOYEE + "', '" + WORKSHOP
                    + "', 'ANA', 'LOPEZ', '1234567890101', '1990-01-01', now(), now())");
            statement.execute("INSERT INTO customer (id, nit, name, created_at, updated_at) "
                    + "VALUES ('" + CUSTOMER + "', '12345678', 'JUAN PEREZ', now(), now())");
        }
    }

    /** Una fila por cada forma que V40 admitia. */
    private void seedV40Sales(Connection connection) throws SQLException {
        seedParties(connection);
        String customer = "'" + CUSTOMER + "'";
        insertV40(connection, NIT_COMPLETED, customer, "'NIT'", "NULL", "COMPLETED");
        insertV40(connection, NIT_REVERSED, customer, "'NIT'", "NULL", "REVERSED");
        insertV40(connection, NIT_CANCELLED, customer, "'NIT'", "NULL", "CANCELLED");
        insertV40(connection, NIT_PENDING, customer, "'NIT'", "NULL", "PENDING");
        insertV40(connection, CF_COMPLETED, "NULL", "'CF'", "'MARIA LOPEZ'", "COMPLETED");
        insertV40(connection, CF_PENDING, "NULL", "'CF'", "'ANA'", "PENDING");
        insertV40(connection, UNRESOLVED_PENDING, "NULL", "'UNRESOLVED'", "'CARLOS GOMEZ'", "PENDING");
        insertV40(connection, UNRESOLVED_CANCELLED, "NULL", "'UNRESOLVED'", "'PEDRO'", "CANCELLED");
    }

    private void insertV40(
            Connection connection, String id, String customerId,
            String taxMode, String customerName, String status) throws SQLException {
        boolean issued = status.equals("COMPLETED") || status.equals("REVERSED");
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO sale (id, workshop_id, employee_id, customer_id, status, "
                    + "completed_at, total, invoice_type, payment_status, payment_method, paid_at, "
                    + "tax_mode, customer_name, created_at, updated_at) VALUES ("
                    + "'" + id + "', '" + WORKSHOP + "', '" + EMPLOYEE + "', " + customerId + ", "
                    + "'" + status + "', " + (issued ? "now()" : "NULL") + ", 10.00, "
                    + (issued ? "'INVOICE'" : "NULL") + ", " + (issued ? "'PAID'" : "'PENDING'") + ", "
                    + (issued ? "'CASH'" : "NULL") + ", " + (issued ? "now()" : "NULL") + ", "
                    + taxMode + ", " + customerName + ", now(), now())");
        }
    }

    /** Inserta con el esquema final; los argumentos llegan como literales SQL. */
    private boolean insert(
            Connection connection, String suffix, String taxMode, String customerId,
            String customerName, String customerNit, String status, boolean savedForLater) {
        boolean issued = status.equals("'COMPLETED'") || status.equals("'REVERSED'");
        String id = "cccccccc-0000-0000-0000-0000000000" + suffix;
        return succeeds(connection, "INSERT INTO sale (id, workshop_id, employee_id, customer_id, "
                + "status, completed_at, total, invoice_type, payment_status, payment_method, paid_at, "
                + "tax_mode, customer_name, customer_nit, saved_for_later, created_at, updated_at) VALUES ("
                + "'" + id + "', '" + WORKSHOP + "', '" + EMPLOYEE + "', " + customerId + ", "
                + status + ", " + (issued ? "now()" : "NULL") + ", 10.00, "
                + (issued ? "'INVOICE'" : "NULL") + ", " + (issued ? "'PAID'" : "'PENDING'") + ", "
                + (issued ? "'CASH'" : "NULL") + ", " + (issued ? "now()" : "NULL") + ", "
                + taxMode + ", " + customerName + ", " + customerNit + ", " + savedForLater
                + ", now(), now())");
    }

    private boolean succeeds(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return true;
        } catch (SQLException rejected) {
            return false;
        }
    }

    /** `tax_mode|customer_name|customer_nit|saved_for_later` de una venta. */
    private String sale(Connection connection, String id) throws SQLException {
        return text(connection, "SELECT tax_mode || '|' || COALESCE(customer_name, 'null') || '|' "
                + "|| COALESCE(customer_nit, 'null') || '|' || "
                + "CASE WHEN saved_for_later THEN 't' ELSE 'f' END FROM sale WHERE id = '" + id + "'");
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
            scripts.sort(Comparator.comparingInt(SaleTaxIdentitySnapshotMigrationTest::versionOf));
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
