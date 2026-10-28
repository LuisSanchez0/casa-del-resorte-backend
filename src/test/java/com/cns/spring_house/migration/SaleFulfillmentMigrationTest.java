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
 * El contrato de V42 frente a ventas que YA EXISTIAN: ninguna linea se
 * convierte en solicitud al taller. Las ventas abiertas antes de la comanda se
 * siguen completando exactamente como antes, y ninguna completada cambia.
 *
 * JDBC a pelo y sin Spring, como las demas pruebas de migracion: aplica
 * V1..V41, siembra a mano y aplica V42.
 */
@TestInstance(Lifecycle.PER_CLASS)
class SaleFulfillmentMigrationTest {

    private static final String DATABASE_PREFIX = "sale_fulfillment_migration_it_";

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    private static final int FULFILLMENT_VERSION = 42;

    private static final String WORKSHOP = "dddddddd-0000-0000-0000-000000000001";
    private static final String EMPLOYEE = "dddddddd-0000-0000-0000-000000000002";
    private static final String PRODUCT = "dddddddd-0000-0000-0000-000000000003";
    private static final String OPEN_SALE = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String COMPLETED_SALE = "eeeeeeee-0000-0000-0000-000000000002";

    private Connection freshDatabase(String suffix) throws SQLException {
        String url = TestDatabases.provision(DATABASE_PREFIX + suffix);
        return DriverManager.getConnection(
                url, TestDatabases.username(), TestDatabases.password());
    }

    @Test
    void noExistingLineBecomesAWorkshopRequest() throws Exception {
        try (Connection connection = freshDatabase("legacy")) {
            applyMigrationsUpTo(connection, FULFILLMENT_VERSION - 1);
            seedLegacySales(connection);
            int sales = count(connection, "SELECT COUNT(*) FROM sale");
            int lines = count(connection, "SELECT COUNT(*) FROM sale_detail");

            applyMigration(connection, FULFILLMENT_VERSION);

            assertThat(count(connection, "SELECT COUNT(*) FROM sale")).isEqualTo(sales);
            assertThat(count(connection, "SELECT COUNT(*) FROM sale_detail")).isEqualTo(lines);
            // Ninguna solicitud fabricada: todas siguen siendo lineas directas.
            assertThat(count(connection,
                    "SELECT COUNT(*) FROM sale_detail WHERE fulfillment_status IS NOT NULL")).isZero();
            assertThat(count(connection,
                    "SELECT COUNT(*) FROM sale_detail WHERE fulfillment_type IS NOT NULL")).isZero();
            // Ninguna venta "en preparacion" ni "lista": nunca enviaron nada.
            assertThat(count(connection,
                    "SELECT COUNT(*) FROM sale WHERE fulfillment_status <> 'NONE'")).isZero();
            // Lo emitido queda intacto.
            assertThat(text(connection,
                    "SELECT status || '/' || total FROM sale WHERE id = '" + COMPLETED_SALE + "'"))
                    .isEqualTo("COMPLETED/100.00");
        }
    }

    @Test
    void theNewColumnsOnlyAcceptCoherentShapes() throws Exception {
        try (Connection connection = freshDatabase("shapes")) {
            applyMigrationsUpTo(connection, FULFILLMENT_VERSION);
            seedLegacySales(connection);

            // Sin enviar, sin ruta todavia: valida.
            assertThat(insertLine(connection, "01", "'PENDING'", "NULL", "NULL")).isTrue();
            // Servicio sin enviar, ya con ruta: valida.
            assertThat(insertLine(connection, "02", "'PENDING'", "'SERVICE'", "NULL")).isTrue();
            // Enviada y con ruta: valida.
            assertThat(insertLine(connection, "03", "'QUEUED'", "'STOCK_RELEASE'", "'Revisar'")).isTrue();
            // Enviada SIN ruta: nunca.
            assertThat(insertLine(connection, "04", "'QUEUED'", "NULL", "NULL")).isFalse();
            assertThat(insertLine(connection, "05", "'FULFILLED'", "NULL", "NULL")).isFalse();
            // Ruta sin estado: nunca.
            assertThat(insertLine(connection, "06", "NULL", "'SERVICE'", "NULL")).isFalse();
            // Valores que no existen.
            assertThat(insertLine(connection, "07", "'DONE'", "'SERVICE'", "NULL")).isFalse();
            assertThat(insertLine(connection, "08", "'QUEUED'", "'CONTROL'", "NULL")).isFalse();
            // Instrucciones en blanco no son instrucciones.
            assertThat(insertLine(connection, "09", "'PENDING'", "NULL", "'   '")).isFalse();
            // Un bloqueo siempre dice por que; en revision de precio, con su minimo.
            assertThat(insertBlocked(connection, "10", "'MATERIAL'", "NULL")).isTrue();
            assertThat(insertBlocked(connection, "11", "'PRICE_REVIEW'", "260.00")).isTrue();
            assertThat(insertBlocked(connection, "12", "NULL", "NULL")).isFalse();
            assertThat(insertBlocked(connection, "13", "'PRICE_REVIEW'", "NULL")).isFalse();
            assertThat(insertBlocked(connection, "14", "'MATERIAL'", "10.00")).isFalse();
            assertThat(insertBlocked(connection, "15", "'OTRO'", "NULL")).isFalse();
            // Fuera de un bloqueo no hay motivo.
            assertThat(insertSucceeds(connection, """
                    INSERT INTO sale_detail (id, sale_id, product_id, quantity, price, sub_total,
                                             fulfillment_status, fulfillment_type, fulfillment_block_reason)
                    VALUES ('ffffffff-0000-0000-0000-000000000116', '%s', '%s', 1, 10.00, 10.00,
                            'QUEUED', 'TRANSFORMATION', 'MATERIAL')
                    """.formatted(OPEN_SALE, PRODUCT))).isFalse();
            // La preparacion de la venta solo admite sus tres valores.
            assertThat(insertSucceeds(connection,
                    "UPDATE sale SET fulfillment_status = 'DONE' WHERE id = '" + OPEN_SALE + "'"))
                    .isFalse();
        }
    }

    // ── Datos de partida ─────────────────────────────────────────

    private void seedLegacySales(Connection connection) throws SQLException {
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
                INSERT INTO product (id, sku, family_id, name, attributes, created_at, updated_at)
                SELECT '%s', 'LEGACY-1', family.id, 'HOJA LEGADA', '{"largo": 100}'::jsonb,
                       now(), now()
                FROM family WHERE family.code = 'HR'
                """.formatted(PRODUCT));
            statement.execute("""
                INSERT INTO sale (id, workshop_id, employee_id, status, completed_at, total,
                                  invoice_type, payment_status, payment_method, paid_at,
                                  tax_mode, customer_name, saved_for_later, created_at, updated_at)
                VALUES
                  ('%1$s', '%3$s', '%4$s', 'PENDING', NULL, 50.00, NULL, 'PENDING', NULL, NULL,
                   'UNRESOLVED', 'CARLOS', true, now(), now()),
                  ('%2$s', '%3$s', '%4$s', 'COMPLETED', now(), 100.00, 'INVOICE', 'PAID', 'CASH',
                   now(), 'CF', NULL, false, now(), now())
                """.formatted(OPEN_SALE, COMPLETED_SALE, WORKSHOP, EMPLOYEE));
            statement.execute("""
                INSERT INTO sale_detail (id, sale_id, product_id, quantity, price, sub_total)
                VALUES
                  ('ffffffff-0000-0000-0000-000000000001', '%1$s', '%3$s', 1, 50.00, 50.00),
                  ('ffffffff-0000-0000-0000-000000000002', '%2$s', '%3$s', 1, 100.00, 100.00)
                """.formatted(OPEN_SALE, COMPLETED_SALE, PRODUCT));
        }
    }

    private boolean insertLine(
            Connection connection, String suffix, String status, String type, String instructions) {
        return insertSucceeds(connection, """
                INSERT INTO sale_detail (id, sale_id, product_id, quantity, price, sub_total,
                                         fulfillment_status, fulfillment_type, instructions)
                VALUES ('ffffffff-0000-0000-0000-0000000001%s', '%s', '%s', 1, 10.00, 10.00,
                        %s, %s, %s)
                """.formatted(suffix, OPEN_SALE, PRODUCT, status, type, instructions));
    }

    private boolean insertBlocked(
            Connection connection, String suffix, String reason, String requiredPrice) {
        return insertSucceeds(connection, """
                INSERT INTO sale_detail (id, sale_id, product_id, quantity, price, sub_total,
                                         fulfillment_status, fulfillment_type,
                                         fulfillment_block_reason, fulfillment_required_price)
                VALUES ('ffffffff-0000-0000-0000-0000000001%s', '%s', '%s', 1, 10.00, 10.00,
                        'BLOCKED', 'TRANSFORMATION', %s, %s)
                """.formatted(suffix, OPEN_SALE, PRODUCT, reason, requiredPrice));
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
            scripts.sort(Comparator.comparingInt(SaleFulfillmentMigrationTest::versionOf));
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
