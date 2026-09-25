package com.cns.spring_house.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Ninguna clase que levante un contexto de Spring puede quedarse sin decir
 * contra que base corre.
 *
 * <h2>Por que una prueba que lee el codigo fuente</h2>
 *
 * {@link TestDatabaseGuard} corta el caso en EJECUCION, pero solo cuando esa
 * clase se ejecuta. Esta prueba lo dice antes y en un sitio: quien anada un
 * {@code @SpringBootTest} sin datasource propio ve un fallo que nombra su
 * fichero y explica que anadir, en lugar de un "Failed to load
 * ApplicationContext" enterrado en el contexto de otra clase.
 *
 * Las dos hacen falta. El guardia protege la base; esta protege al que escribe.
 *
 * Se lee el TEXTO de los ficheros y no se escanea el classpath a proposito: las
 * anotaciones que interesan son de tipo SOURCE/CLASS y la pregunta es sobre lo
 * que hay escrito, no sobre lo que se cargo.
 */
class TestDatabaseIsolationTest {

    private static final Path TEST_SOURCES = Path.of("src/test/java");

    /** Levantan contexto de Spring y, con el, un datasource. */
    private static final List<String> CONTEXT_ANNOTATIONS =
            List.of("@SpringBootTest", "@DataJpaTest");

    /** La unica forma admitida de fijar la base en este repositorio. */
    private static final String ISOLATION_ANNOTATION = "@DynamicPropertySource";

    @Test
    void everyContextLoadingTestDeclaresItsOwnIsolatedDatabase() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path source : testSources()) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            boolean loadsContext = CONTEXT_ANNOTATIONS.stream()
                    .anyMatch(annotation -> declares(code, annotation));
            if (loadsContext && !declares(code, ISOLATION_ANNOTATION)) {
                offenders.add(TEST_SOURCES.relativize(source).toString());
            }
        }

        assertThat(offenders)
                .as("""
                    Estas pruebas levantan un contexto de Spring sin redefinir el \
                    datasource, asi que heredan el del .env y migran la base de \
                    DESARROLLO. Anade un @DynamicPropertySource con \
                    TestDatabases.provision("<algo>_it"), como hacen las demas \
                    pruebas de base de datos del proyecto.""")
                .isEmpty();
    }

    /**
     * Y el patron del guardia acepta de verdad todas las bases que la suite
     * declara. Si alguien anadiera una prueba con una base llamada
     * {@code mi_prueba_db}, el guardia la tumbaria en ejecucion; aqui se ve
     * antes y con su nombre.
     */
    @Test
    void everyDeclaredTestDatabaseMatchesTheIsolatedNamingConvention() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path source : testSources()) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            for (String database : declaredDatabaseNames(code)) {
                if (!TestDatabases.isIsolated(database)
                        && !TestDatabases.isIsolated(database + "sufijo")) {
                    offenders.add(
                            TEST_SOURCES.relativize(source) + " -> " + database);
                }
            }
        }

        assertThat(offenders)
                .as("Nombres de base de pruebas fuera de la convencion _it")
                .isEmpty();
    }

    // ── Que el aprovisionamiento manual no vuelva ────────────────

    /**
     * Los unicos ficheros a los que se les permite hablar de crear o borrar
     * bases de datos.
     *
     * {@code TestDatabases} porque es quien lo hace, y su prueba porque
     * comprueba precisamente que se niegue a hacerlo con un nombre que no toca.
     */
    private static final List<String> MAY_PROVISION = List.of(
            "com/cns/spring_house/testsupport/TestDatabases.java",
            "com/cns/spring_house/testsupport/TestDatabasesTest.java",
            "com/cns/spring_house/testsupport/TestDatabaseIsolationTest.java");

    /** Las marcas inequivocas de estar creando una base a mano. */
    private static final List<String> PROVISIONING_MARKERS =
            List.of("CREATE DATABASE", "DROP DATABASE");

    /**
     * Nadie vuelve a escribir su propio DROP/CREATE.
     *
     * Esta es la prueba que impide que el trabajo de migrar 37 clases al helper
     * compartido se deshaga clase a clase. Sin ella, la siguiente prueba de base
     * de datos se copiaria de una vieja —que es como llegaron a existir las 37—
     * y volveria a dejar su base atras, fuera del guardia y fuera de la
     * limpieza.
     *
     * Se buscan las sentencias SQL y no {@code DriverManager}: conectarse a una
     * base ya aprovisionada es legitimo y varias pruebas lo hacen. Lo que no lo
     * es, es CREARLA.
     */
    @Test
    void noTestProvisionsItsOwnDatabase() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path source : testSources()) {
            String relative = TEST_SOURCES.relativize(source).toString();
            if (MAY_PROVISION.contains(relative.replace('\\', '/'))) {
                continue;
            }
            String code = Files.readString(source, StandardCharsets.UTF_8);
            for (String marker : PROVISIONING_MARKERS) {
                if (code.contains(marker)) {
                    offenders.add(relative + " -> " + marker);
                }
            }
        }

        assertThat(offenders)
                .as("""
                    Estas pruebas crean o borran bases de datos por su cuenta.                     Usa TestDatabases.provision("<algo>_it"): comprueba el nombre                     antes de tocar el servidor y registra la base para retirarla                     al terminar la JVM.""")
                .isEmpty();
    }

    /**
     * Y nadie vuelve a parsear las variables de conexion por su cuenta.
     *
     * Habia 37 copias de {@code loadEnvironment}/{@code value} leyendo
     * DB_HOST/DB_PORT/DB_USER/DB_PASS. Una sola ruta de resolucion significa que
     * cambiar como se conecta CI es un cambio en un fichero, no en treinta y
     * siete.
     */
    @Test
    void connectionSettingsAreResolvedInOnePlaceOnly() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path source : testSources()) {
            String relative = TEST_SOURCES.relativize(source).toString();
            if (relative.replace('\\', '/').startsWith("com/cns/spring_house/testsupport/")) {
                continue;
            }
            String code = Files.readString(source, StandardCharsets.UTF_8);
            for (String variable : List.of("DB_HOST", "DB_PORT", "DB_USER", "DB_PASS")) {
                if (code.contains(variable)) {
                    offenders.add(relative + " -> " + variable);
                }
            }
        }

        assertThat(offenders)
                .as("Usa TestDatabases.username()/password()/server() en vez de"
                        + " releer las variables de entorno")
                .isEmpty();
    }

    // ── Lectura del codigo ───────────────────────────────────────

    private static List<Path> testSources() throws IOException {
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * La anotacion aparece DE VERDAD: al principio de su linea, que es como se
     * escribe una anotacion Java.
     *
     * No basta con un {@code contains}: el Javadoc de esta misma clase nombra
     * las tres anotaciones, y la comprobacion se haria fallar a si misma. Se
     * mira linea a linea en vez de limpiar las cadenas con una expresion
     * regular porque la version con regex desbordaba la pila sobre los ficheros
     * de prueba mas largos del proyecto.
     */
    private static boolean declares(String code, String annotation) {
        return stripComments(code).lines()
                .map(String::strip)
                .anyMatch(line -> line.equals(annotation)
                        || line.startsWith(annotation + "(")
                        || line.startsWith(annotation + " "));
    }

    /**
     * Los nombres de base declarados como constante:
     * {@code ... TEMPORARY_DATABASE = "sale_service_it";} y los prefijos que
     * usan las pruebas de migracion.
     */
    private static List<String> declaredDatabaseNames(String code) {
        List<String> names = new ArrayList<>();
        var matcher = java.util.regex.Pattern
                .compile("(?:TEMPORARY_DATABASE|DATABASE_PREFIX|DATABASE)\\s*=\\s*\"([^\"]*)\"")
                .matcher(stripComments(code));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String stripComments(String code) {
        return code
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

}
