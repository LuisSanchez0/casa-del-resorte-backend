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

import com.cns.spring_house.testsupport.TestDatabases;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * El contrato de la migracion V32: crea el esquema de proveedores, lotes y
 * asignaciones de costo SIN tocar los datos transaccionales que ya existan.
 *
 * Una version anterior de V32 llevaba ademas un reinicio destructivo de ventas
 * e inventario. Ese bloque se retiro del proyecto, asi que esta prueba
 * comprueba lo contrario de lo que comprobaba antes: que un taller con ventas,
 * inventario, transformaciones y bitacora SOBREVIVE intacto a la migracion.
 *
 * Se ejecuta a pelo con JDBC y sin contexto de Spring porque lo que se prueba es
 * el SQL: aplicar V1..V31, sembrar a mano, aplicar V32 y contar. Con Flyway ya
 * ejecutado por el arranque de la aplicacion no se podria observar el ANTES.
 */
@TestInstance(Lifecycle.PER_CLASS)
class AcquisitionSchemaMigrationTest {

    /**
     * Una base POR PRUEBA. Compartirla haria que la segunda encontrara el
     * esquema ya migrado por la primera y fallara con "relation already
     * exists", y ademas ataria el resultado al orden de ejecucion, que JUnit no
     * garantiza.
     */
    private static final String DATABASE_PREFIX = "acquisition_migration_it_";

    private static final Path MIGRATIONS =
            Path.of("src/main/resources/db/migration");

    /** Version que introduce el esquema de lotes y proveedores. */
    private static final int ACQUISITION_VERSION = 32;

    /** Base recien creada y vacia, exclusiva de quien la pide. */
    /**
     * Base recien creada y vacia, una por caso.
     *
     * Cada caso pide la SUYA —{@code DATABASE_PREFIX + suffix}— y todas pasan
     * por {@link TestDatabases}: alli el nombre se comprueba antes de llegar al
     * SQL, de modo que esta prueba no puede borrar la base de desarrollo, y lo
     * creado queda registrado para retirarlo al terminar la JVM.
     */
    private Connection freshDatabase(String suffix) throws SQLException {
        String url = TestDatabases.provision(DATABASE_PREFIX + suffix);
        return DriverManager.getConnection(
                url, TestDatabases.username(), TestDatabases.password());
    }

    /**
     * Todo el ciclo en una sola prueba porque es UNA historia: el estado previo,
     * la migracion y el resultado. Partirla obligaria a montar la base tres
     * veces para observar tres fotos del mismo proceso.
     */
    @Test
    void createsTheAcquisitionSchemaWithoutTouchingExistingData() throws Exception {
        try (Connection connection = freshDatabase("acquisition")) {

            applyMigrationsUpTo(connection, ACQUISITION_VERSION - 1);
            seedTransactionalData(connection);

            // ── ANTES: hay datos transaccionales de sobra ──────────
            int sales = count(connection, "sale");
            int saleDetails = count(connection, "sale_detail");
            int inventories = count(connection, "inventory");
            int inventoryItems = count(connection, "inventory_item");
            int workResults = count(connection, "work_result");
            int transformations = count(connection, "transformation");

            assertThat(sales).isPositive();
            assertThat(saleDetails).isPositive();
            assertThat(inventories).isPositive();
            assertThat(inventoryItems).isPositive();
            assertThat(workResults).isPositive();
            assertThat(transformations).isPositive();

            // ── La migracion, que no puede fallar por una FK ───────
            applyMigration(connection, ACQUISITION_VERSION);

            // ── DESPUES: nada transaccional se ha perdido ──────────
            // Es la mitad del contrato: V32 anade capacidades, no reinicia el
            // taller. Una migracion que se llevara por delante las ventas
            // obligaria a volver a darlo todo de alta.
            assertThat(count(connection, "sale")).isEqualTo(sales);
            assertThat(count(connection, "sale_detail")).isEqualTo(saleDetails);
            assertThat(count(connection, "inventory")).isEqualTo(inventories);
            assertThat(count(connection, "inventory_item")).isEqualTo(inventoryItems);
            assertThat(count(connection, "work_result")).isEqualTo(workResults);
            assertThat(count(connection, "transformation")).isEqualTo(transformations);
            assertThat(count(connection, "transformation_item")).isPositive();
            assertThat(count(connection, "daily_log")).isPositive();
            assertThat(count(connection, "sale_action")).isPositive();
            assertThat(count(connection, "job_log")).isPositive();

            // ── El catalogo tampoco se toca ───────────────────────
            assertThat(count(connection, "workshop")).isEqualTo(1);
            assertThat(count(connection, "employee")).isEqualTo(1);
            assertThat(count(connection, "user_account")).isEqualTo(1);
            assertThat(count(connection, "customer")).isEqualTo(1);
            assertThat(count(connection, "product")).isEqualTo(2);
            assertThat(count(connection, "job")).isEqualTo(1);
            assertThat(count(connection, "vehicle_movement")).isEqualTo(1);
            assertThat(count(connection, "family")).isPositive();
            assertThat(count(connection, "role")).isPositive();

            // ── Las tablas nuevas existen y nacen vacias ───────────
            assertThat(count(connection, "supplier")).isZero();
            assertThat(count(connection, "inventory_acquisition")).isZero();
            assertThat(count(connection, "sale_detail_cost_allocation")).isZero();

            // ── Y `sale_detail` gana su columna de costo historico ─
            // NULL, no cero: las lineas anteriores a la migracion no tienen
            // costo de adquisicion conocido, y cero afirmaria que fueron gratis.
            assertThat(scalar(connection, """
                    SELECT COUNT(*) FROM sale_detail WHERE cost_total IS NULL
                    """)).isEqualTo(saleDetails);
        }
    }

    /**
     * Una compra nueva entra por el modelo nuevo y deja su lote. Comprueba que
     * el esquema resultante es USABLE, no solo que exista.
     */
    @Test
    void acceptsAnAcquisitionBackedStockEntry() throws Exception {
        try (Connection connection = freshDatabase("usable")) {

            applyMigrationsUpTo(connection, ACQUISITION_VERSION);

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO workshop (id, name, address, is_active, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-000000000001', 'T', 'D', true, now(), now())
                    ON CONFLICT DO NOTHING
                """);
                statement.execute("""
                    INSERT INTO family (id, code, name, requires_model, category, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-000000000002', 'RESETC', 'RESET FAM', false, 'NORMAL', now(), now())
                    ON CONFLICT DO NOTHING
                """);
                statement.execute("""
                    INSERT INTO product (id, sku, family_id, name, attributes, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-000000000003', 'RESET-SKU',
                            'aaaaaaaa-0000-0000-0000-000000000002', 'PROD', '{}'::jsonb, now(), now())
                    ON CONFLICT DO NOTHING
                """);
                statement.execute("""
                    INSERT INTO inventory (id, product_id, workshop_id, price, cost, stock, min_stock, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-000000000004',
                            'aaaaaaaa-0000-0000-0000-000000000003',
                            'aaaaaaaa-0000-0000-0000-000000000001',
                            200.00, 110.00, 15, 0, now(), now())
                """);
                statement.execute("""
                    INSERT INTO supplier (id, workshop_id, name, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-000000000005',
                            'aaaaaaaa-0000-0000-0000-000000000001',
                            'Resortes Guatemala', now(), now())
                """);
                statement.execute("""
                    INSERT INTO inventory_acquisition (
                        id, workshop_id, inventory_id, supplier_id, origin,
                        acquisition_type, quantity_received, quantity_remaining,
                        unit_cost, acquired_at, created_at, updated_at)
                    VALUES ('aaaaaaaa-0000-0000-0000-000000000006',
                            'aaaaaaaa-0000-0000-0000-000000000001',
                            'aaaaaaaa-0000-0000-0000-000000000004',
                            'aaaaaaaa-0000-0000-0000-000000000005',
                            'PURCHASE', 'NATIONAL', 15, 15, 110.00, now(), now(), now())
                """);
            }

            // El stock y el remanente cuadran, que es el invariante del modelo.
            assertThat(scalar(connection,
                    "SELECT stock FROM inventory WHERE id = 'aaaaaaaa-0000-0000-0000-000000000004'"))
                    .isEqualTo(15);
            assertThat(scalar(connection, """
                    SELECT COALESCE(SUM(quantity_remaining), 0)
                    FROM inventory_acquisition
                    WHERE inventory_id = 'aaaaaaaa-0000-0000-0000-000000000004'
                    """)).isEqualTo(15);
        }
    }

    // ── Aplicacion de migraciones ────────────────────────────────

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * Aplica las migraciones en ORDEN DE VERSION NUMERICA, no alfabetico. Es la
     * distincion que rompe cualquier atajo con `ls`: alfabeticamente, `V10` va
     * antes que `V2` y la primera tabla no existiria todavia.
     */
    private void applyMigrationsUpTo(Connection connection, int maxVersion)
            throws IOException, SQLException {
        for (Path script : migrationsInOrder()) {
            int version = versionOf(script);
            if (version <= maxVersion) {
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
            scripts.sort(Comparator.comparingInt(AcquisitionSchemaMigrationTest::versionOf));
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

    // ── Datos de partida ─────────────────────────────────────────

    /**
     * Un taller con TODO lo que el reinicio tiene que tocar y todo lo que NO
     * debe tocar, incluidas las cadenas de FK mas largas: una venta con detalle
     * que apunta a un item y a un work_result, ese work_result a un job_log, y
     * una transformacion con su item.
     */
    private void seedTransactionalData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO workshop (id, name, address, is_active, created_at, updated_at)
                VALUES ('11111111-1111-1111-1111-111111111111', 'TALLER', 'DIR', true, now(), now());

                INSERT INTO employee (id, workshop_id, first_name, last_name, dpi, birth_date, created_at, updated_at)
                VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111',
                        'NOMBRE', 'APELLIDO', '1234567890123', DATE '1990-01-01', now(), now());

                INSERT INTO user_account (id, employee_id, role_id, username, password, is_active, created_at, updated_at)
                SELECT '33333333-3333-3333-3333-333333333333',
                       '22222222-2222-2222-2222-222222222222', role.id, 'usuario', 'secreto', true, now(), now()
                FROM role WHERE role.name = 'ADMIN' LIMIT 1;

                INSERT INTO customer (id, nit, name, created_at, updated_at)
                VALUES ('44444444-4444-4444-4444-444444444444', '12345678', 'CLIENTE', now(), now());

                INSERT INTO brand (id, name, logo_key, created_at, updated_at)
                VALUES ('55555555-5555-5555-5555-555555555555', 'MARCA', 'logo', now(), now());

                INSERT INTO model (id, brand_id, name, year, created_at, updated_at)
                VALUES ('66666666-6666-6666-6666-666666666666', '55555555-5555-5555-5555-555555555555',
                        'MODELO', 2020, now(), now());

                INSERT INTO family (id, code, name, requires_model, category, created_at, updated_at)
                VALUES ('77777777-7777-7777-7777-777777777777', 'SEEDN', 'FAMILIA NORMAL', false, 'NORMAL', now(), now()),
                       ('77777777-7777-7777-7777-777777777778', 'SEEDJ', 'FAMILIA JOB', false, 'JOB', now(), now());

                INSERT INTO product (id, sku, family_id, name, attributes, created_at, updated_at)
                VALUES ('88888888-8888-8888-8888-888888888888', 'SKU-1',
                        '77777777-7777-7777-7777-777777777777', 'PRODUCTO', '{}'::jsonb, now(), now()),
                       ('88888888-8888-8888-8888-888888888889', 'SKU-2',
                        '77777777-7777-7777-7777-777777777778', 'SERVICIO', '{}'::jsonb, now(), now());

                INSERT INTO job (id, workshop_id, product_id, price, created_at, updated_at)
                VALUES ('99999999-9999-9999-9999-999999999999', '11111111-1111-1111-1111-111111111111',
                        '88888888-8888-8888-8888-888888888889', 150.00, now(), now());

                INSERT INTO vehicle_movement (id, workshop_id, employee_id, plate, entry, created_at, updated_at)
                VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111',
                        '22222222-2222-2222-2222-222222222222', 'AB123CDE', now(), now(), now());

                INSERT INTO inventory (id, product_id, workshop_id, price, cost, stock, min_stock, created_at, updated_at)
                VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '88888888-8888-8888-8888-888888888888',
                        '11111111-1111-1111-1111-111111111111', 200.00, 100.00, 5, 0, now(), now());

                INSERT INTO inventory_item (id, inventory_id, status, created_at, updated_at)
                VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                        'AVAILABLE', now(), now()),
                       ('cccccccc-cccc-cccc-cccc-ccccccccccc1', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                        'UNAVAILABLE', now(), now());

                -- Auto-referencia: una unidad contenida dentro de otra. Es lo que
                -- obliga a soltar `container_id` antes de borrar la tabla.
                UPDATE inventory_item SET container_id = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
                WHERE id = 'cccccccc-cccc-cccc-cccc-ccccccccccc1';

                INSERT INTO job_log (id, job_id, employee_id, performed_date, created_at, updated_at)
                VALUES ('dddddddd-dddd-dddd-dddd-dddddddddddd', '99999999-9999-9999-9999-999999999999',
                        '22222222-2222-2222-2222-222222222222', now(), now(), now());

                -- `ck_work_result_source` obliga a que el trabajo venga de UNA
                -- fuente: o de una unidad fisica, o de una bitacora de servicio.
                -- Se siembran los dos casos para que el reinicio tenga que
                -- resolver las dos cadenas de claves ajenas, no solo una.
                INSERT INTO work_result (id, workshop_id, item_id, job_log_id, status, created_at, updated_at)
                VALUES ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111',
                        'cccccccc-cccc-cccc-cccc-cccccccccccc', NULL,
                        'PENDING', now(), now()),
                       ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', '11111111-1111-1111-1111-111111111111',
                        NULL, 'dddddddd-dddd-dddd-dddd-dddddddddddd',
                        'PENDING', now(), now());

                INSERT INTO transformation (id, workshop_id, employee_id, performed_date, created_at, updated_at)
                VALUES ('ffffffff-ffff-ffff-ffff-ffffffffffff', '11111111-1111-1111-1111-111111111111',
                        '22222222-2222-2222-2222-222222222222', now(), now(), now());

                INSERT INTO transformation_item (id, transformation_id, item_id, role)
                VALUES ('ffffffff-ffff-ffff-ffff-fffffffffff1', 'ffffffff-ffff-ffff-ffff-ffffffffffff',
                        'cccccccc-cccc-cccc-cccc-cccccccccccc', 'INPUT');

                INSERT INTO sale (id, workshop_id, employee_id, customer_id, status, completed_at,
                                  total, invoice_type, payment_status, payment_method, paid_at,
                                  created_at, updated_at)
                VALUES ('12121212-1212-1212-1212-121212121212', '11111111-1111-1111-1111-111111111111',
                        '22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444',
                        'COMPLETED', now(), 200.00, 'INVOICE', 'PAID', 'CASH', now(), now(), now());

                INSERT INTO sale_detail (id, sale_id, product_id, item_id, work_result_id,
                                         quantity, price, sub_total)
                VALUES ('13131313-1313-1313-1313-131313131313', '12121212-1212-1212-1212-121212121212',
                        '88888888-8888-8888-8888-888888888888', 'cccccccc-cccc-cccc-cccc-cccccccccccc',
                        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 1, 200.00, 200.00);

                INSERT INTO sale_action (id, sale_id, performed_by, action_type, reason, created_at, updated_at)
                VALUES ('14141414-1414-1414-1414-141414141414', '12121212-1212-1212-1212-121212121212',
                        '22222222-2222-2222-2222-222222222222', 'CANCEL_PENDING', 'motivo', now(), now());
            """);

            seedDailyLog(connection);
        }
    }

    /**
     * `daily_log` cambio de forma en V26, asi que sus columnas se leen del
     * esquema en vez de escribirse a mano: la prueba tiene que seguir sembrando
     * una bitacora aunque esa tabla vuelva a evolucionar.
     */
    private void seedDailyLog(Connection connection) throws SQLException {
        List<String> columns = columnsOf(connection, "daily_log");
        StringBuilder names = new StringBuilder(
                "id, workshop_id, employee_id, sale_id");
        StringBuilder values = new StringBuilder(
                "'15151515-1515-1515-1515-151515151515', "
                + "'11111111-1111-1111-1111-111111111111', "
                + "'22222222-2222-2222-2222-222222222222', "
                + "'12121212-1212-1212-1212-121212121212'");

        if (columns.contains("created_at")) {
            names.append(", created_at");
            values.append(", now()");
        }
        if (columns.contains("updated_at")) {
            names.append(", updated_at");
            values.append(", now()");
        }
        if (columns.contains("performed_date")) {
            names.append(", performed_date");
            values.append(", CURRENT_DATE");
        }
        if (columns.contains("business_date")) {
            names.append(", business_date");
            values.append(", CURRENT_DATE");
        }
        if (columns.contains("description")) {
            names.append(", description");
            values.append(", 'bitacora'");
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO daily_log (" + names + ") VALUES ("
                    + values + ")");
        }
    }

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

    // ── Lecturas ─────────────────────────────────────────────────

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

}
