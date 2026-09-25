package com.cns.spring_house.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las bases de datos de PRUEBA: como se nombran, como se crean y —sobre todo—
 * como se impide que una prueba toque la base de desarrollo.
 *
 * <h2>El accidente que esto evita</h2>
 *
 * {@code application.yml} importa el {@code .env} del desarrollador, asi que
 * cualquier contexto de Spring que NO redefina el datasource resuelve
 * {@code DB_NAME=spring_house_db} y Flyway MIGRA la base de trabajo de quien
 * ejecuta las pruebas. No hay error ni aviso: la suite pasa en verde y el
 * esquema de desarrollo queda alterado.
 *
 * Paso de verdad: un {@code @SpringBootTest} sin {@code @DynamicPropertySource}
 * aplico una migracion nueva sobre {@code spring_house_db}.
 *
 * <h2>La convencion del repositorio</h2>
 *
 * Toda base de integracion termina en {@code _it}, opcionalmente con un sufijo
 * cuando una misma clase levanta varias:
 *
 * <pre>
 * sale_service_it
 * cash_closure_service_it
 * cash_closure_dimensions_it_backfill
 * </pre>
 *
 * El patron NO es una lista de nombres concretos: las pruebas de migracion
 * generan bases por caso, y enumerarlas obligaria a tocar esta clase cada vez
 * que se anade una.
 *
 * <h2>Orden</h2>
 *
 * La comprobacion corre ANTES de cualquier DROP, CREATE o migracion. Fallar
 * despues de haber escrito no sirve de nada: el dano ya esta hecho.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /**
     * Un nombre de base de integracion valido.
     *
     * {@code _it} al final, o {@code _it_} seguido del caso concreto. Cualquier
     * otra cosa —empezando por {@code spring_house_db}— se rechaza.
     */
    private static final Pattern ISOLATED_DATABASE =
            Pattern.compile("^[a-z][a-z0-9_]*_it(_[a-z0-9_]+)?$");

    /** `jdbc:postgresql://host:puerto/NOMBRE?parametros` -> NOMBRE. */
    private static final Pattern JDBC_DATABASE =
            Pattern.compile("^jdbc:postgresql://[^/]+/([^?;]+)");

    /**
     * Las bases creadas por esta JVM de pruebas, para poder retirarlas al
     * terminar en vez de dejarlas sueltas en el servidor.
     */
    private static final Set<String> PROVISIONED =
            Collections.synchronizedSet(new LinkedHashSet<>());

    static {
        // La limpieza va en un gancho de apagado y no en un @AfterAll porque el
        // TestContext CACHEA los contextos hasta que la JVM muere: mientras
        // haya contexto vivo, su pool de Hikari sigue conectado y un DROP
        // normal fallaria. Aqui ya no queda nadie a quien molestar, y el FORCE
        // corta cualquier conexion rezagada.
        Runtime.getRuntime().addShutdownHook(new Thread(TestDatabases::dropProvisioned));
    }

    // ── El guardia ───────────────────────────────────────────────

    /**
     * Comprueba que el nombre pertenece a una base de PRUEBA.
     *
     * @throws IllegalStateException si no lo es. Es un error y no un aviso a
     *         proposito: un log se pierde entre miles de lineas de una suite, y
     *         lo que esta en juego es la base de trabajo de alguien.
     */
    public static void assertIsolated(String databaseName) {
        if (databaseName == null || !ISOLATED_DATABASE.matcher(databaseName).matches()) {
            throw new IllegalStateException(
                    "Refusing to run tests against non-test database: " + databaseName
                    + ". Las pruebas solo pueden usar bases cuyo nombre termine"
                    + " en _it (por ejemplo sale_service_it) o _it_<caso>.");
        }
    }

    /**
     * Lo mismo, a partir de la URL JDBC ya resuelta.
     *
     * Una URL que no sea PostgreSQL se deja pasar: no hay base de desarrollo
     * que proteger detras de un H2 en memoria, y rechazarla romperia cualquier
     * prueba que decidiera usarlo.
     */
    public static void assertIsolatedJdbcUrl(String jdbcUrl) {
        String database = databaseNameOf(jdbcUrl);
        if (database != null) {
            assertIsolated(database);
        }
    }

    /** El nombre de base dentro de una URL JDBC de PostgreSQL, o null. */
    public static String databaseNameOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        Matcher matcher = JDBC_DATABASE.matcher(jdbcUrl.trim());
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Sin lanzar: para que una prueba pueda comprobar el criterio. */
    public static boolean isIsolated(String databaseName) {
        return databaseName != null && ISOLATED_DATABASE.matcher(databaseName).matches();
    }

    // ── Aprovisionamiento ────────────────────────────────────────

    /**
     * Crea la base de pruebas indicada, vacia, y devuelve su URL JDBC.
     *
     * Se borra y se vuelve a crear: una prueba tiene que partir de lo mismo
     * siempre, y no de lo que dejo la ejecucion anterior.
     *
     * El DROP pasa por {@link #assertIsolated(String)} primero. Esa es la
     * garantia dura de esta clase: aqui no hay forma de escribir un DROP contra
     * {@code spring_house_db}, porque el nombre no llega al SQL sin superar el
     * patron.
     */
    public static String provision(String databaseName) {
        assertIsolated(databaseName);

        String server = server();
        try (Connection connection = DriverManager.getConnection(
                        server + "postgres", username(), password());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + databaseName);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "No se pudo preparar la base de pruebas " + databaseName, exception);
        }

        PROVISIONED.add(databaseName);
        return server + databaseName;
    }

    /**
     * Retira UNA base ya terminada, sin esperar al final de la JVM.
     *
     * Para las pruebas que no levantan contexto de Spring y pueden soltarla en
     * su {@code @AfterAll}: su unica conexion ya esta cerrada, asi que liberarla
     * en el acto es mejor que tenerla ocupando el servidor el resto de la suite.
     * Las que si levantan contexto NO pueden hacer esto: el TestContext cachea
     * el contexto hasta que muere la JVM y su pool sigue conectado.
     */
    public static void drop(String databaseName) {
        assertIsolated(databaseName);
        PROVISIONED.remove(databaseName);

        try (Connection connection = DriverManager.getConnection(
                        server() + "postgres", username(), password());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "No se pudo retirar la base de pruebas " + databaseName, exception);
        }
    }

    /**
     * Retira las bases creadas por esta JVM.
     *
     * Corre en el gancho de apagado, asi que NO puede lanzar: para cuando se
     * ejecuta, la suite ya dio su resultado y una excepcion aqui solo
     * conseguiria un stack trace confuso detras de un build verde.
     *
     * Lo que si hace es DECIRLO. Un fallo de limpieza se escribe en stderr con
     * el nombre de la base y el error, porque el sintoma de tragarselo es una
     * base que se queda ahi sin que nadie sepa por que, y eso es justo lo que
     * este mecanismo existe para evitar.
     *
     * Solo toca lo que ESTA JVM aprovisiono. Nunca recorre {@code pg_database}
     * buscando nombres que terminen en _it: alguno podria pertenecer a otra
     * ejecucion o a otra persona trabajando contra el mismo servidor.
     */
    static void dropProvisioned() {
        Set<String> pending;
        synchronized (PROVISIONED) {
            pending = new LinkedHashSet<>(PROVISIONED);
            PROVISIONED.clear();
        }
        if (pending.isEmpty()) {
            return;
        }

        try (Connection connection = DriverManager.getConnection(
                        server() + "postgres", username(), password());
                Statement statement = connection.createStatement()) {
            for (String database : pending) {
                // El guardia otra vez: nada sale de aqui sin volver a pasarlo.
                assertIsolated(database);
                try {
                    statement.execute(
                            "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
                } catch (SQLException exception) {
                    reportCleanupFailure(database, exception);
                }
            }
        } catch (SQLException exception) {
            // Ni siquiera se pudo abrir la conexion de administracion: entonces
            // NINGUNA se retiro, y hay que nombrarlas todas.
            for (String database : pending) {
                reportCleanupFailure(database, exception);
            }
        }
    }

    /** El fallo, con nombre y causa, donde se pueda ver. */
    private static void reportCleanupFailure(String database, SQLException exception) {
        System.err.println(
                "[TestDatabases] No se pudo retirar la base de pruebas '" + database
                + "': " + exception.getClass().getSimpleName()
                + ": " + exception.getMessage()
                + ". Queda en el servidor y habra que borrarla a mano.");
    }

    // ── Conexion ─────────────────────────────────────────────────
    //
    // El servidor, el usuario y la contrasena SI salen del entorno del
    // desarrollador: es el PostgreSQL que tiene levantado. Lo que nunca sale de
    // ahi es el NOMBRE de la base, que es justo lo que decide si se escribe
    // sobre datos de trabajo o sobre una base desechable.

    public static String server() {
        return "jdbc:postgresql://" + value("DB_HOST", "localhost")
                + ":" + value("DB_PORT", "5432") + "/";
    }

    public static String username() {
        return value("DB_USER", "postgres");
    }

    public static String password() {
        return value("DB_PASS", null);
    }

    /**
     * Una variable del entorno, o del `.env` del proyecto, o su valor por
     * defecto. La variable de entorno gana: es lo que permite que CI fije sus
     * credenciales sin tocar ningun fichero del repositorio.
     */
    public static String value(String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        String fromFile = environment().getProperty(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        if (fallback == null) {
            throw new IllegalStateException("Falta la variable " + key);
        }
        return fallback;
    }

    private static Properties cachedEnvironment;

    private static synchronized Properties environment() {
        if (cachedEnvironment != null) {
            return cachedEnvironment;
        }
        Properties properties = new Properties();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try (InputStream stream = Files.newInputStream(file)) {
                properties.load(stream);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        cachedEnvironment = properties;
        return properties;
    }

}
