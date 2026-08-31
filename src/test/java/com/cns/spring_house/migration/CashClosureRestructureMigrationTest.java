package com.cns.spring_house.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
 * El contrato de la migracion V33 frente a un cierre que YA EXISTIA.
 *
 * NINGUNA migracion borra `cash_closure`, asi que una base que ya operaba llega
 * a V33 con cierres confirmados dentro. Eso importa por una razon concreta: el
 * cierre mensual agrega el total cobrado desde los escalares de `cash_closure`
 * pero lee el desglose por forma de pago de la tabla hija. Un dia antiguo que
 * aportara su total sin aportar sus filas de metodo romperia justo la
 * correspondencia que el cierre promete —que el desglose sume el total—, y lo
 * haria EN SILENCIO: el mes seguiria cuadrando consigo mismo salvo por un
 * descuadre que nadie sabria de donde viene.
 *
 * Se ejecuta a pelo con JDBC y sin contexto de Spring porque lo que se prueba es
 * el SQL: aplicar V1..V32, sembrar el cierre viejo a mano, aplicar V33 y mirar.
 * Con Flyway ya ejecutado por el arranque de la aplicacion no se podria observar
 * el ANTES.
 */
@TestInstance(Lifecycle.PER_CLASS)
class CashClosureRestructureMigrationTest {

    private static final String DATABASE_PREFIX = "cash_closure_migration_it_";

    private static final Path MIGRATIONS =
            Path.of("src/main/resources/db/migration");

    /** Ultima version con el cierre en su forma antigua. */
    private static final int BEFORE_RESTRUCTURE = 32;

    /** La version que reestructura el cierre. */
    private static final int RESTRUCTURE = 33;

    private static final String CLOSURE = "44444444-4444-4444-4444-444444444444";

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

    /**
     * Toda la historia en una prueba: el cierre viejo, la migracion y lo que
     * queda. Partirla obligaria a montar la base varias veces para mirar tres
     * fotos del mismo proceso.
     */
    @Test
    void anExistingClosureKeepsItsMoneyAndGainsItsPaymentBreakdown() throws Exception {
        try (Connection connection = freshDatabase("existing")) {
            applyMigrationsUpTo(connection, BEFORE_RESTRUCTURE);
            seedLegacyClosure(connection);

            // ANTES: el arqueo esta, el desglose por metodo no existe siquiera
            // como tabla.
            assertThat(columnsOf(connection, "cash_closure"))
                    .contains("opening_cash", "counted_cash", "difference", "notes",
                            "pending_exchange_amount");
            assertThat(tableExists(connection, "cash_closure_payment_method")).isFalse();

            applyMigration(connection, RESTRUCTURE);

            // El cierre SIGUE AHI. La migracion cambia su forma, no lo borra:
            // un cierre confirmado es el documento de un dia y ademas es lo que
            // impide seguir vendiendo en el.
            assertThat(count(connection, "cash_closure")).isEqualTo(1);

            // El arqueo se fue del modelo, que es lo que se pidio.
            assertThat(columnsOf(connection, "cash_closure"))
                    .doesNotContain("opening_cash", "expected_cash", "counted_cash",
                            "difference", "notes", "pending_exchange_amount");

            // Las cifras contables NO se tocaron.
            assertThat(money(connection, "SELECT total_collected FROM cash_closure"))
                    .isEqualByComparingTo("2400.00");
            assertThat(money(connection, "SELECT total_invoiced FROM cash_closure"))
                    .isEqualByComparingTo("2700.00");

            // Y el desglose por forma de pago aparece, con las tres formas
            // aunque una valga cero: una forma sin cobros vale Q0.00, y decirlo
            // es informacion.
            Map<String, BigDecimal> byMethod = paymentAmounts(connection);
            assertThat(byMethod).containsOnlyKeys("CASH", "CARD", "TRANSFER");
            assertThat(byMethod.get("CASH")).isEqualByComparingTo("1500.00");
            assertThat(byMethod.get("CARD")).isEqualByComparingTo("900.00");
            assertThat(byMethod.get("TRANSFER")).isEqualByComparingTo("0.00");

            // LA razon de existir de este relleno: el desglose suma EXACTAMENTE
            // el total cobrado del dia.
            assertThat(money(connection,
                    "SELECT SUM(amount) FROM cash_closure_payment_method"))
                    .isEqualByComparingTo(
                            money(connection, "SELECT total_collected FROM cash_closure"));

            // Los contadores viajan con los importes, no se pierden.
            assertThat(scalar(connection, "SELECT payment_count FROM"
                    + " cash_closure_payment_method WHERE payment_method = 'CASH'"))
                    .isEqualTo(3);
        }
    }

    /**
     * Lo que la migracion NO puede reconstruir, y por que no lo finge.
     *
     * El desglose por articulo y los documentos cambiarios del dia nunca se
     * guardaron: no hay de donde sacarlos salvo releyendo `sale_detail`, que es
     * justo lo que un snapshot confirmado no puede hacer —hoy esas ventas
     * pueden estar revertidas, y una cambiaria que ese dia estaba pendiente
     * puede estar cobrada—. Reconstruirlas produciria un cierre que dice algo
     * distinto de lo que dijo. Queda el 0 explicito, y esta prueba lo fija para
     * que sea una decision documentada y no una sorpresa.
     */
    @Test
    void theDetailThatWasNeverCapturedIsLeftEmptyRatherThanInvented() throws Exception {
        try (Connection connection = freshDatabase("gaps")) {
            applyMigrationsUpTo(connection, BEFORE_RESTRUCTURE);
            seedLegacyClosure(connection);
            applyMigration(connection, RESTRUCTURE);

            assertThat(count(connection, "cash_closure_item")).isZero();
            assertThat(count(connection, "cash_closure_exchange_invoice")).isZero();
            assertThat(money(connection, "SELECT items_billed_amount FROM cash_closure"))
                    .isEqualByComparingTo("0.00");
            assertThat(money(connection,
                    "SELECT issued_exchange_pending_amount FROM cash_closure"))
                    .isEqualByComparingTo("0.00");
        }
    }

    /**
     * Sobre una base sin cierres el relleno no inventa ninguna fila. Parece
     * obvio, pero un `INSERT ... SELECT` mal escrito puede rendir una fila por
     * metodo sin cierre al que colgarla.
     */
    @Test
    void anEmptyDatabaseGainsNoRows() throws Exception {
        try (Connection connection = freshDatabase("empty")) {
            applyMigrationsUpTo(connection, BEFORE_RESTRUCTURE);

            applyMigration(connection, RESTRUCTURE);

            assertThat(count(connection, "cash_closure_payment_method")).isZero();
            assertThat(count(connection, "cash_closure_item")).isZero();
            assertThat(count(connection, "cash_closure_exchange_invoice")).isZero();
        }
    }

    // ── Datos de partida ─────────────────────────────────────────

    /** Un cierre tal y como lo escribia el modelo anterior, arqueo incluido. */
    private void seedLegacyClosure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO workshop (id, name, address, is_active, created_at, updated_at)
                VALUES ('11111111-1111-1111-1111-111111111111', 'TALLER', 'DIR', true, now(), now());

                INSERT INTO employee (id, workshop_id, first_name, last_name, dpi, birth_date, created_at, updated_at)
                VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111',
                        'NOMBRE', 'APELLIDO', '1234567890123', DATE '1990-01-01', now(), now());

                INSERT INTO cash_closure (
                    id, workshop_id, closed_by, business_date,
                    opening_cash,
                    invoice_count, invoice_total,
                    exchange_invoice_count, exchange_invoice_total, total_invoiced,
                    cash_payment_count, cash_payments,
                    card_payment_count, card_payments,
                    transfer_payment_count, transfer_payments,
                    total_collected,
                    exchange_collected_count, exchange_collected_amount,
                    previous_exchange_collected_amount,
                    pending_exchange_amount,
                    expected_cash, counted_cash, difference, notes,
                    created_at, updated_at)
                VALUES (
                    '%s', '11111111-1111-1111-1111-111111111111',
                    '22222222-2222-2222-2222-222222222222', DATE '2026-03-14',
                    200.00,
                    4, 2000.00,
                    1, 700.00, 2700.00,
                    3, 1500.00,
                    2, 900.00,
                    0, 0.00,
                    2400.00,
                    1, 400.00,
                    400.00,
                    700.00,
                    1700.00, 1690.00, -10.00, 'faltaron Q10 del vuelto',
                    now(), now());
                """.formatted(CLOSURE));
        }
    }

    // ── Utilidades ───────────────────────────────────────────────

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    /** Base recien creada y vacia, exclusiva de quien la pide. */
    private Connection freshDatabase(String suffix) throws SQLException {
        String database = DATABASE_PREFIX + suffix;
        try (Connection admin = DriverManager.getConnection(
                server + "postgres", username, password);
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + database);
        }
        return DriverManager.getConnection(server + database, username, password);
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
                    CashClosureRestructureMigrationTest::versionOf));
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

    private List<String> columnsOf(Connection connection, String table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT column_name FROM information_schema.columns"
                                + " WHERE table_name = '" + table + "'")) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        return columns;
    }

    private boolean tableExists(Connection connection, String table)
            throws SQLException {
        return scalar(connection, "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_name = '" + table + "'") > 0;
    }

    /** Importes por forma de pago, en el orden en que la tabla los devuelve. */
    private Map<String, BigDecimal> paymentAmounts(Connection connection)
            throws SQLException {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT payment_method, amount FROM cash_closure_payment_method")) {
            while (rows.next()) {
                amounts.put(rows.getString(1), rows.getBigDecimal(2));
            }
        }
        return amounts;
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

    private BigDecimal money(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBigDecimal(1);
        }
    }

    private static Properties loadEnvironment() throws IOException {
        Properties properties = new Properties();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try (InputStream stream = Files.newInputStream(file)) {
                properties.load(stream);
            }
        }
        return properties;
    }

    private static String value(Properties environment, String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        String fromFile = environment.getProperty(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        if (fallback == null) {
            throw new IllegalStateException("Falta la variable " + key);
        }
        return fallback;
    }
}
