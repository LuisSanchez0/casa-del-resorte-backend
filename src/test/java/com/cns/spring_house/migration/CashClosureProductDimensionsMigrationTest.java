package com.cns.spring_house.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.cns.spring_house.testsupport.TestDatabases;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * El contrato de la migracion V37, que da a `cash_closure_item` sus dimensiones
 * comerciales —familia, marca y modelo— para que el segundo PDF del cierre
 * pueda agrupar sin volver a mirar el catalogo.
 *
 * Se ejecuta a pelo con JDBC y sin contexto de Spring porque lo que se prueba es
 * el SQL: aplicar V1..V36, sembrar cierres a mano, aplicar V37 y mirar. Con
 * Flyway ya ejecutado por el arranque de la aplicacion no se podria observar el
 * ANTES, que es justo lo que aqui importa.
 *
 * Lo que se fija:
 *
 * <pre>
 * 1. las columnas llegan, y son NULLABLE
 * 2. un cierre que ya existia se rellena desde el catalogo
 * 3. marca y modelo nulos cuando la familia no trabaja con modelo
 * 4. un producto borrado deja las dimensiones en nulo, no las inventa
 * 5. los CHECK impiden una fila a medias
 * </pre>
 */
@TestInstance(Lifecycle.PER_CLASS)
class CashClosureProductDimensionsMigrationTest {

    private static final String DATABASE_PREFIX = "cash_closure_dimensions_it_";

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** Ultima version sin dimensiones en el articulo del cierre. */
    private static final int BEFORE_DIMENSIONS = 36;

    /** La version que las anade. */
    private static final int DIMENSIONS = 37;

    private static final String WORKSHOP = "11111111-1111-1111-1111-111111111111";
    private static final String EMPLOYEE = "22222222-2222-2222-2222-222222222222";
    private static final String CLOSURE = "44444444-4444-4444-4444-444444444444";

    private static final String BRAND = "55555555-5555-5555-5555-555555555555";
    private static final String MODEL = "66666666-6666-6666-6666-666666666666";
    private static final String MODEL_FAMILY = "77777777-7777-7777-7777-777777777777";
    private static final String PLAIN_FAMILY = "88888888-8888-8888-8888-888888888888";

    private static final String HOJA_80 = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String HOJA_90 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String PERNO = "aaaaaaaa-0000-0000-0000-000000000003";
    /** Vendido en su dia y borrado del catalogo despues. */
    private static final String GHOST = "aaaaaaaa-0000-0000-0000-000000000004";


    // ── Desde cero ───────────────────────────────────────────────

    /**
     * Toda la cadena de migraciones sobre una base limpia, hasta V37 incluida.
     *
     * Es la unica prueba que garantiza que un despliegue nuevo arranca: las
     * demas siembran a mano y podrian pasar con un script que no compone con los
     * anteriores.
     */
    @Test
    void everyMigrationAppliesFromACleanDatabase() throws Exception {
        try (Connection connection = freshDatabase("clean")) {
            applyMigrationsUpTo(connection, DIMENSIONS);

            assertThat(columnsOf(connection, "cash_closure_item"))
                    .contains("family_id", "family_name",
                            "brand_id", "brand_name",
                            "model_id", "model_name");
            assertThat(count(connection, "cash_closure_item")).isZero();
        }
    }

    /**
     * Las seis columnas son NULLABLE, y tiene que serlo incluso `family_id`.
     *
     * `product.family_id` SI es NOT NULL, pero esta tabla ya tiene filas de
     * cierres confirmados: exigir un valor obligaria a inventar una familia para
     * el producto que ya no existe, y un documento historico no admite datos
     * inventados.
     */
    @Test
    void theNewColumnsAreNullable() throws Exception {
        try (Connection connection = freshDatabase("nullable")) {
            applyMigrationsUpTo(connection, DIMENSIONS);

            assertThat(nullableColumnsOf(connection, "cash_closure_item"))
                    .contains("family_id", "family_name",
                            "brand_id", "brand_name",
                            "model_id", "model_name");
        }
    }

    /** El indice que sirve la agrupacion del reporte. */
    @Test
    void theGroupingIndexExists() throws Exception {
        try (Connection connection = freshDatabase("index")) {
            applyMigrationsUpTo(connection, DIMENSIONS);

            assertThat(scalar(connection,
                    "SELECT COUNT(*) FROM pg_indexes WHERE tablename ="
                            + " 'cash_closure_item' AND indexname ="
                            + " 'idx_cash_closure_item_dimensions"
                            + "'")).isEqualTo(1);
        }
    }

    // ── Los cierres que ya existian ──────────────────────────────

    /**
     * Un cierre confirmado ANTES de V37 recupera sus dimensiones desde el
     * catalogo, que es la unica fuente que hay: cuando se guardo, estas columnas
     * no existian y nadie copio nada.
     *
     * Las dos hojas del mismo modelo quedan con las MISMAS tres identidades, que
     * es lo que despues las agrupa en una sola fila del reporte pese a ser dos
     * productos distintos.
     */
    @Test
    void anExistingClosureIsBackfilledFromTheCatalogue() throws Exception {
        try (Connection connection = freshDatabase("backfill")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);

            // ANTES: la tabla no sabe nada de familias ni marcas.
            assertThat(columnsOf(connection, "cash_closure_item"))
                    .doesNotContain("family_id", "brand_id", "model_id");

            applyMigration(connection, DIMENSIONS);

            // Las filas SIGUEN AHI: la migracion las completa, no las rehace.
            assertThat(count(connection, "cash_closure_item")).isEqualTo(4);

            assertThat(text(connection, dimensionOf(HOJA_80, "family_name")))
                    .isEqualTo("HOJA DE RESORTE PESADA");
            assertThat(text(connection, dimensionOf(HOJA_80, "brand_name")))
                    .isEqualTo("Toyota");
            assertThat(text(connection, dimensionOf(HOJA_80, "model_name")))
                    .isEqualTo("Hilux");

            // Las dos medidas comparten las tres identidades: un solo grupo.
            assertThat(text(connection, dimensionOf(HOJA_90, "family_id")))
                    .isEqualTo(text(connection, dimensionOf(HOJA_80, "family_id")))
                    .isEqualTo(MODEL_FAMILY);
            assertThat(text(connection, dimensionOf(HOJA_90, "brand_id")))
                    .isEqualTo(BRAND);
            assertThat(text(connection, dimensionOf(HOJA_90, "model_id")))
                    .isEqualTo(MODEL);

            // Las cifras no se tocaron.
            assertThat(scalar(connection, "SELECT SUM(quantity) FROM cash_closure_item"))
                    .isEqualTo(10);
        }
    }

    /**
     * Una familia que no trabaja con modelo se queda SIN marca y SIN modelo.
     *
     * El nulo significa NO APLICA, y es lo que el reporte imprime como guion. No
     * se le asigna una marca generica para "rellenar", que dejaria al taller
     * leyendo una marca que nunca vendio.
     */
    @Test
    void aFamilyWithoutModelGetsNoBrandAndNoModel() throws Exception {
        try (Connection connection = freshDatabase("plain")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);
            applyMigration(connection, DIMENSIONS);

            assertThat(text(connection, dimensionOf(PERNO, "family_name")))
                    .isEqualTo("PERNO");
            assertThat(text(connection, dimensionOf(PERNO, "brand_id"))).isNull();
            assertThat(text(connection, dimensionOf(PERNO, "brand_name"))).isNull();
            assertThat(text(connection, dimensionOf(PERNO, "model_id"))).isNull();
            assertThat(text(connection, dimensionOf(PERNO, "model_name"))).isNull();
        }
    }

    /**
     * Un producto que ya no esta en el catalogo deja las seis columnas en nulo.
     *
     * Su fila NO se borra: sus cifras son dinero facturado y siguen contando en
     * el cierre. Lo que no se hace es asignarle una familia cualquiera con tal
     * de que la tabla quede llena.
     */
    @Test
    void aDeletedProductKeepsItsFiguresAndGetsNoInventedDimensions() throws Exception {
        try (Connection connection = freshDatabase("ghost")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);
            applyMigration(connection, DIMENSIONS);

            assertThat(text(connection, dimensionOf(GHOST, "family_id"))).isNull();
            assertThat(text(connection, dimensionOf(GHOST, "family_name"))).isNull();
            assertThat(text(connection, dimensionOf(GHOST, "brand_id"))).isNull();

            assertThat(money(connection,
                    "SELECT billed_amount FROM cash_closure_item"
                            + " WHERE product_id = '" + GHOST + "'"))
                    .isEqualByComparingTo("77.00");
        }
    }

    /** Sin cierres previos no se inventa ninguna fila. */
    @Test
    void anEmptyDatabaseGainsNoRows() throws Exception {
        try (Connection connection = freshDatabase("empty")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);

            applyMigration(connection, DIMENSIONS);

            assertThat(count(connection, "cash_closure_item")).isZero();
        }
    }

    // ── El limite de lo que se puede reconstruir hacia atras ─────

    /**
     * EL caso honesto de esta migracion.
     *
     * Un cierre se confirma. DESPUES —y todavia antes de que V37 exista— alguien
     * renombra la familia y la marca en el catalogo. Cuando la migracion por fin
     * corre, lo unico que puede copiar es lo que el catalogo dice EN ESE
     * MOMENTO: el nombre nuevo, no el que la pieza tenia el dia de la venta.
     *
     * Esta prueba fija ese comportamiento como lo que es —mejor esfuerzo, no
     * historia exacta— en vez de dejarlo como una sorpresa. La etiqueta original
     * no esta guardada en ninguna parte: las columnas no existian cuando ese
     * cierre se confirmo, y ni `sale_detail` ni el propio cierre la registraron.
     *
     * Para los cierres confirmados DESPUES de V37 esto no aplica: sus
     * dimensiones se congelan al confirmar y ninguna edicion posterior las toca.
     * Eso lo cubre {@code CashClosureServiceDatabaseTest}.
     */
    @Test
    void aCatalogueRenamedBeforeV37LeavesTheMigrationOnlyItsMigrationTimeNames()
            throws Exception {
        try (Connection connection = freshDatabase("renamed")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);

            // El cierre ya esta confirmado con estos nombres...
            assertThat(text(connection,
                    "SELECT item_name FROM cash_closure_item"
                            + " WHERE product_id = '" + HOJA_80 + "'"))
                    .isEqualTo("HOJA 80");

            // ...y AHORA, antes de migrar, el catalogo cambia por completo.
            execute(connection, """
                UPDATE family SET name = 'MUELLES PESADOS' WHERE id = '%s';
                UPDATE brand  SET name = 'TOYOTA MOTORS'   WHERE id = '%s';
                UPDATE model  SET name = 'HILUX MK2'       WHERE id = '%s';
                """.formatted(MODEL_FAMILY, BRAND, MODEL));

            applyMigration(connection, DIMENSIONS);

            // Lo que quedo guardado es el nombre DEL MOMENTO DE MIGRAR.
            assertThat(text(connection, dimensionOf(HOJA_80, "family_name")))
                    .isEqualTo("MUELLES PESADOS")
                    .isNotEqualTo("HOJA DE RESORTE PESADA");
            assertThat(text(connection, dimensionOf(HOJA_80, "brand_name")))
                    .isEqualTo("TOYOTA MOTORS");
            assertThat(text(connection, dimensionOf(HOJA_80, "model_name")))
                    .isEqualTo("HILUX MK2");

            // Los IDENTIFICADORES si son los correctos: la agrupacion sigue
            // siendo la buena aunque la etiqueta sea la de despues.
            assertThat(text(connection, dimensionOf(HOJA_80, "family_id")))
                    .isEqualTo(MODEL_FAMILY);
            assertThat(text(connection, dimensionOf(HOJA_80, "brand_id")))
                    .isEqualTo(BRAND);
            assertThat(text(connection, dimensionOf(HOJA_90, "family_id")))
                    .isEqualTo(MODEL_FAMILY);
        }
    }

    /**
     * Y las cifras no se tocan en ningun caso: la migracion completa etiquetas,
     * no reescribe dinero.
     */
    @Test
    void theBackfillNeverTouchesTheMoney() throws Exception {
        try (Connection connection = freshDatabase("money")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);

            java.math.BigDecimal before = money(connection,
                    "SELECT SUM(billed_amount) FROM cash_closure_item");
            int quantityBefore = scalar(connection,
                    "SELECT SUM(quantity) FROM cash_closure_item");

            applyMigration(connection, DIMENSIONS);

            assertThat(money(connection,
                    "SELECT SUM(billed_amount) FROM cash_closure_item"))
                    .isEqualByComparingTo(before);
            assertThat(scalar(connection, "SELECT SUM(quantity) FROM cash_closure_item"))
                    .isEqualTo(quantityBefore);

            // Incluida la fila huerfana, que conserva su importe sin familia.
            assertThat(money(connection,
                    "SELECT SUM(billed_amount) FROM cash_closure_item"
                            + " WHERE family_id IS NULL"))
                    .isEqualByComparingTo("77.00");
        }
    }

    // ── Las reglas que impiden una fila a medias ─────────────────

    /**
     * Una marca sin modelo se rechaza.
     *
     * En este dominio la marca se alcanza A TRAVES del modelo
     * (`product.model_id` -> `model.brand_id`, NOT NULL), asi que una fila con
     * marca y sin modelo describe algo que el catalogo no puede producir. El
     * CHECK lo dice en la base y no solo en el codigo que hoy escribe la fila.
     */
    @Test
    void aBrandWithoutItsModelIsRejected() throws Exception {
        try (Connection connection = freshDatabase("halfbrand")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);
            applyMigration(connection, DIMENSIONS);

            assertThatThrownBy(() -> execute(connection,
                    "UPDATE cash_closure_item SET model_id = NULL, model_name = NULL"
                            + " WHERE product_id = '" + HOJA_80 + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_cash_closure_item_brand_with_model");
        }
    }

    /**
     * Un id sin su nombre se rechaza: seria una fila que agrupa pero no se puede
     * imprimir, y el PDF historico dejaria un hueco donde va la familia.
     */
    @Test
    void anIdentityWithoutItsPrintableNameIsRejected() throws Exception {
        try (Connection connection = freshDatabase("halfname")) {
            applyMigrationsUpTo(connection, BEFORE_DIMENSIONS);
            seedCatalogueAndClosure(connection);
            applyMigration(connection, DIMENSIONS);

            assertThatThrownBy(() -> execute(connection,
                    "UPDATE cash_closure_item SET family_name = NULL"
                            + " WHERE product_id = '" + PERNO + "'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_cash_closure_item_family_named");
        }
    }

    // ── Datos de partida ─────────────────────────────────────────

    /**
     * Un catalogo con las dos formas que el dominio admite —familia con modelo y
     * familia sin el— y un cierre confirmado que vendio de las dos, mas un
     * producto que despues se borro.
     */
    private void seedCatalogueAndClosure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO workshop (id, name, address, is_active, created_at, updated_at)
                VALUES ('%s', 'TALLER', 'DIR', true, now(), now());

                INSERT INTO employee (id, workshop_id, first_name, last_name, dpi, birth_date, created_at, updated_at)
                VALUES ('%s', '%s', 'NOMBRE', 'APELLIDO', '1234567890123', DATE '1990-01-01', now(), now());

                INSERT INTO brand (id, name, logo_key, created_at, updated_at)
                VALUES ('%s', 'Toyota', 'sin-logo', now(), now());

                INSERT INTO model (id, brand_id, name, year_from, year_to, created_at, updated_at)
                VALUES ('%s', '%s', 'Hilux', 2015, 2025, now(), now());

                INSERT INTO family (id, code, name, requires_model, category, created_at, updated_at)
                VALUES ('%s', 'HRT', 'HOJA DE RESORTE PESADA', true, 'SPECIAL', now(), now()),
                       ('%s', 'PRN', 'PERNO', false, 'NORMAL', now(), now());

                INSERT INTO product (id, sku, family_id, name, attributes, model_id, created_at, updated_at)
                VALUES ('%s', 'HR-80', '%s', 'HOJA 80', '{"largo": 80}'::jsonb, '%s', now(), now()),
                       ('%s', 'HR-90', '%s', 'HOJA 90', '{"largo": 90}'::jsonb, '%s', now(), now()),
                       ('%s', 'PR-12', '%s', 'PERNO 1/2', '{}'::jsonb, NULL, now(), now());

                INSERT INTO cash_closure (
                    id, workshop_id, closed_by, business_date,
                    invoice_count, invoice_total,
                    exchange_invoice_count, exchange_invoice_total, total_invoiced,
                    cash_payment_count, cash_payments,
                    card_payment_count, card_payments,
                    transfer_payment_count, transfer_payments,
                    total_collected,
                    exchange_collected_count, exchange_collected_amount,
                    previous_exchange_collected_amount,
                    issued_exchange_pending_amount, items_billed_amount,
                    created_at, updated_at)
                VALUES ('%s', '%s', '%s', DATE '2026-03-14',
                    1, 500.00, 0, 0.00, 500.00,
                    1, 500.00, 0, 0.00, 0, 0.00, 500.00,
                    0, 0.00, 0.00, 0.00, 500.00,
                    now(), now());

                INSERT INTO cash_closure_item (
                    id, cash_closure_id, product_id, item_name, item_type,
                    quantity, billed_amount, created_at, updated_at)
                VALUES (gen_random_uuid(), '%s', '%s', 'HOJA 80', 'SPECIAL', 1, 100.00, now(), now()),
                       (gen_random_uuid(), '%s', '%s', 'HOJA 90', 'SPECIAL', 2, 150.00, now(), now()),
                       (gen_random_uuid(), '%s', '%s', 'PERNO 1/2', 'NORMAL', 3, 173.00, now(), now()),
                       (gen_random_uuid(), '%s', '%s', 'PRODUCTO BORRADO', 'NORMAL', 4, 77.00, now(), now());
                """.formatted(
                        WORKSHOP,
                        EMPLOYEE, WORKSHOP,
                        BRAND,
                        MODEL, BRAND,
                        MODEL_FAMILY, PLAIN_FAMILY,
                        HOJA_80, MODEL_FAMILY, MODEL,
                        HOJA_90, MODEL_FAMILY, MODEL,
                        PERNO, PLAIN_FAMILY,
                        CLOSURE, WORKSHOP, EMPLOYEE,
                        CLOSURE, HOJA_80,
                        CLOSURE, HOJA_90,
                        CLOSURE, PERNO,
                        CLOSURE, GHOST));
        }
    }

    // ── Utilidades ───────────────────────────────────────────────

    private static String dimensionOf(String productId, String column) {
        return "SELECT " + column + " FROM cash_closure_item"
                + " WHERE product_id = '" + productId + "'";
    }

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    /**
     * Base recien creada y vacia, exclusiva de quien la pide.
     *
     * Pasa por {@link TestDatabases} y no por un DROP escrito aqui: alli el
     * nombre se comprueba ANTES de llegar al SQL, asi que esta prueba no puede
     * borrar la base de desarrollo ni por error de tecleo. Y lo que se crea
     * queda apuntado para retirarlo al terminar la JVM, en vez de dejar once
     * bases sueltas en el servidor.
     */
    private Connection freshDatabase(String suffix) throws SQLException {
        String url = TestDatabases.provision(DATABASE_PREFIX + suffix);
        return DriverManager.getConnection(
                url, TestDatabases.username(), TestDatabases.password());
    }

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
            scripts.sort(Comparator.comparingInt(
                    CashClosureProductDimensionsMigrationTest::versionOf));
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
        execute(connection, Files.readString(script, StandardCharsets.UTF_8));
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        return columnNames(connection,
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "'");
    }

    private List<String> nullableColumnsOf(Connection connection, String table)
            throws SQLException {
        return columnNames(connection,
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "'"
                        + " AND is_nullable = 'YES'");
    }

    private List<String> columnNames(Connection connection, String sql)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
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

    private String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private java.math.BigDecimal money(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBigDecimal(1);
        }
    }

}
