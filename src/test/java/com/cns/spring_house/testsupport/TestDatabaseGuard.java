package com.cns.spring_house.testsupport;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Impide que CUALQUIER contexto de Spring de las pruebas arranque contra una
 * base que no sea de pruebas.
 *
 * <h2>Por que existe</h2>
 *
 * El fallo real no fue una prueba mal escrita: fue una prueba que NO dijo nada.
 * {@code @SpringBootTest} sin {@code @DynamicPropertySource} hereda el
 * datasource de {@code application.yml}, que importa el {@code .env} del
 * desarrollador. Flyway migraba entonces {@code spring_house_db}, la base de
 * trabajo, y no fallaba nada: la suite pasaba en verde y el unico rastro era una
 * fila nueva en su {@code flyway_schema_history}.
 *
 * Arreglar esa clase concreta no impide que manana aparezca otra igual. Un
 * {@link ContextCustomizerFactory} se aplica a TODOS los contextos de prueba sin
 * tocar ninguna de las clases existentes: se descubre por
 * {@code META-INF/spring.factories} y el framework lo consulta al construir cada
 * contexto.
 *
 * <h2>Cuando comprueba, y por que no antes</h2>
 *
 * La comprobacion NO puede hacerse dentro de {@link ContextCustomizer#customizeContext}:
 * los customizers corren en orden, y el de Spring —{@code DynamicPropertiesContextCustomizer},
 * el que publica lo que declara {@code @DynamicPropertySource}— va DESPUES. Mirar
 * el entorno ahi lee todavia el valor del {@code .env} y da un falso positivo
 * contra las 37 pruebas que si redefinen su base. Se comprobo: reventaban todas.
 *
 * Asi que lo que se registra aqui es un {@link BeanFactoryPostProcessor}, que
 * corre durante el refresco, con TODAS las fuentes de propiedades ya en su
 * sitio y antes de instanciar un solo bean. Para entonces
 * {@code spring.datasource.url} es el valor definitivo, y ni el {@code DataSource}
 * ni {@code FlywayMigrationInitializer} —que se crean al final del refresco—
 * existen todavia. Se falla antes de abrir la conexion, que es el unico momento
 * en que fallar sirve de algo.
 *
 * <h2>Que deja pasar</h2>
 *
 * Un contexto sin {@code spring.datasource.url} no tiene base que proteger, y
 * una URL que no sea PostgreSQL tampoco: detras de un H2 en memoria no hay datos
 * de nadie.
 *
 * Y, sobre todo, un contexto SIN {@code DataSource}. Las rodajas web
 * —{@code @WebMvcTest}— heredan la propiedad del {@code .env} igual que
 * cualquier otro contexto, pero no autoconfiguran el datasource: nunca abren
 * una conexion y Flyway no corre. Rechazarlas seria un falso positivo que tumba
 * pruebas que jamas tocaron la base. Se comprobo: {@code SecurityConfigTest} y
 * sus cinco anidadas caian por esto.
 */
public class TestDatabaseGuard implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return new DatasourceIsolationCustomizer();
    }

    /**
     * Es una clase con nombre y con {@code equals} estable, no una lambda: el
     * customizer forma parte de la CLAVE con la que el TestContext cachea los
     * contextos. Dos instancias que no se consideraran iguales partirian esa
     * cache y cada clase de prueba levantaria su propio contexto, multiplicando
     * el tiempo de la suite y las conexiones abiertas contra PostgreSQL.
     */
    static final class DatasourceIsolationCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(
                ConfigurableApplicationContext context,
                MergedContextConfiguration mergedConfig) {
            context.addBeanFactoryPostProcessor(
                    new DatasourceIsolationCheck(context.getEnvironment()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DatasourceIsolationCustomizer;
        }

        @Override
        public int hashCode() {
            return DatasourceIsolationCustomizer.class.hashCode();
        }
    }

    /** La comprobacion, ya con el entorno resuelto y antes de ningun bean. */
    record DatasourceIsolationCheck(ConfigurableEnvironment environment)
            implements BeanFactoryPostProcessor {

        private static final String DATASOURCE_URL = "spring.datasource.url";

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!definesADataSource(beanFactory)) {
                return;
            }

            String url = environment.getProperty(DATASOURCE_URL);
            if (url == null || url.isBlank()) {
                return;
            }

            String database = TestDatabases.databaseNameOf(url);
            if (database == null || TestDatabases.isIsolated(database)) {
                return;
            }

            throw new IllegalStateException(
                    "Refusing to run tests against non-test database: " + database
                    + " (resuelto desde " + DATASOURCE_URL + "=" + url + ")."
                    + " Esta clase de prueba no redefine el datasource, asi que"
                    + " hereda el del .env de desarrollo. Anade un"
                    + " @DynamicPropertySource que apunte a una base _it,"
                    + " como hacen las demas pruebas de base de datos.");
        }

        /**
         * Si este contexto va a tener un {@code DataSource}.
         *
         * Se mira la DEFINICION del bean, sin instanciarlo
         * ({@code allowEagerInit = false}): crear el datasource aqui abriria la
         * conexion que precisamente se quiere impedir. Para cuando corre este
         * post-procesador las autoconfiguraciones ya estan registradas, asi que
         * la definicion existe si va a existir.
         */
        private static boolean definesADataSource(
                ConfigurableListableBeanFactory beanFactory) {
            return beanFactory.getBeanNamesForType(DataSource.class, true, false).length > 0;
        }
    }

}
