package com.cns.spring_house.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El criterio que decide si una base es de PRUEBA.
 *
 * Es la pieza de la que cuelga todo lo demas: si este patron acepta
 * {@code spring_house_db}, el guardia deja pasar una migracion sobre la base de
 * trabajo de alguien y nadie se entera hasta que el esquema ya cambio.
 */
class TestDatabasesTest {

    // ── B. Acepta lo que el repositorio ya usa ───────────────────

    /**
     * Nombres reales de la suite, no inventados para la prueba. Si el patron se
     * endureciera de mas, estas son las pruebas que dejarian de arrancar.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "sale_service_it",
        "cash_closure_service_it",
        "cash_closure_calc_it",
        "spring_house_context_it",
        "vehicle_movement_dashboard_it",
        "special_transformation_cross_family_it",
        // Las pruebas de migracion generan una base por caso, con sufijo.
        "cash_closure_dimensions_it_backfill",
        "cash_closure_migration_it_existing",
        "model_year_range_migration_it_before"
    })
    void acceptsTheIsolatedDatabasesTheSuiteReallyUses(String database) {
        assertThat(TestDatabases.isIsolated(database)).isTrue();
        assertThatCode(() -> TestDatabases.assertIsolated(database))
                .doesNotThrowAnyException();
    }

    // ── C. Rechaza la base de desarrollo ─────────────────────────

    /**
     * LA prueba de este fichero. {@code spring_house_db} es la base que una
     * prueba migro sin querer, y el mensaje tiene que nombrarla: un
     * "configuracion invalida" generico no le dice a nadie que acaba de salvarse
     * de tocar su base de trabajo.
     */
    @Test
    void rejectsTheDevelopmentDatabase() {
        assertThat(TestDatabases.isIsolated("spring_house_db")).isFalse();
        assertThatThrownBy(() -> TestDatabases.assertIsolated("spring_house_db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Refusing to run tests against non-test database: spring_house_db");
    }

    // ── D. Rechaza cualquier otra base que no sea de pruebas ─────

    @ParameterizedTest
    @ValueSource(strings = {
        "spring_house_db",
        "spring_house",
        "postgres",
        "produccion",
        "spring_house_prod",
        // Parecidas pero NO validas: el sufijo tiene que ser exactamente _it.
        "spring_house_integration",
        "sale_service_its",
        "it_sale_service",
        "sale_service_IT",
        // Vacias o con formas raras.
        "",
        " ",
        "_it"
    })
    void rejectsAnythingThatIsNotAnIsolatedDatabase(String database) {
        assertThat(TestDatabases.isIsolated(database)).isFalse();
        assertThatThrownBy(() -> TestDatabases.assertIsolated(database))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to run tests against non-test database");
    }

    @Test
    void rejectsANullDatabase() {
        assertThat(TestDatabases.isIsolated(null)).isFalse();
        assertThatThrownBy(() -> TestDatabases.assertIsolated(null))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── La URL resuelta ──────────────────────────────────────────
    //
    // El guardia del contexto no ve un nombre suelto: ve la URL JDBC que
    // resolvio Spring. Extraer mal el nombre lo dejaria ciego.

    @Test
    void readsTheDatabaseNameOutOfAJdbcUrl() {
        assertThat(TestDatabases.databaseNameOf(
                "jdbc:postgresql://localhost:5432/sale_service_it"))
                .isEqualTo("sale_service_it");
        assertThat(TestDatabases.databaseNameOf(
                "jdbc:postgresql://db.interno:6543/cash_closure_service_it?ssl=true"))
                .isEqualTo("cash_closure_service_it");
        assertThat(TestDatabases.databaseNameOf(
                "jdbc:postgresql://localhost:5432/spring_house_db"))
                .isEqualTo("spring_house_db");
    }

    /**
     * Una URL que no es PostgreSQL no tiene base de desarrollo detras, asi que
     * el guardia la deja pasar en vez de romper una prueba que use H2.
     */
    @Test
    void ignoresUrlsThatAreNotPostgres() {
        assertThat(TestDatabases.databaseNameOf("jdbc:h2:mem:cualquiera")).isNull();
        assertThat(TestDatabases.databaseNameOf(null)).isNull();
        assertThatCode(() -> TestDatabases.assertIsolatedJdbcUrl("jdbc:h2:mem:x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TestDatabases.assertIsolatedJdbcUrl(null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAJdbcUrlPointingAtTheDevelopmentDatabase() {
        assertThatThrownBy(() -> TestDatabases.assertIsolatedJdbcUrl(
                        "jdbc:postgresql://localhost:5432/spring_house_db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring_house_db");
    }

    // ── El aprovisionamiento no puede saltarse el guardia ────────

    /**
     * {@code provision} es el UNICO sitio de la suite que ejecuta
     * {@code DROP DATABASE}. Que compruebe el nombre ANTES de conectarse es lo
     * que hace imposible, por construccion, un DROP contra la base de
     * desarrollo: la prueba ni siquiera llega a abrir una conexion.
     */
    @Test
    void provisioningRefusesANonTestDatabaseBeforeTouchingTheServer() {
        assertThatThrownBy(() -> TestDatabases.provision("spring_house_db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Refusing to run tests against non-test database: spring_house_db");
    }

}
