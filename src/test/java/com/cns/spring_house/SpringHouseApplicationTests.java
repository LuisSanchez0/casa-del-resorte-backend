package com.cns.spring_house;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.testsupport.TestDatabases;

/**
 * Que la aplicacion entera levante: todos los beans, todas las dependencias y
 * el esquema completo aplicado por Flyway.
 *
 * <h2>Por que esta clase lleva su propia base</h2>
 *
 * Esta prueba levanta el contexto COMPLETO, y eso incluye Flyway. Sin el
 * {@code @DynamicPropertySource} de abajo heredaba el datasource de
 * {@code application.yml}, que importa el {@code .env} del desarrollador: la
 * prueba se conectaba a {@code spring_house_db} y MIGRABA la base de trabajo de
 * quien ejecutara la suite.
 *
 * No fallaba nada. El contexto arrancaba, la prueba pasaba en verde y el unico
 * rastro era una fila nueva en el {@code flyway_schema_history} de una base que
 * ninguna prueba deberia haber tocado. Por eso el arreglo no es solo esta
 * anotacion, sino tambien {@code TestDatabaseGuard}, que corta el caso general.
 *
 * {@code Replace.NONE} por el mismo motivo que el resto de pruebas de base de
 * datos del proyecto: el esquema lo crea Flyway sobre PostgreSQL real, y una
 * base embebida no reproduce ni el JSONB ni los indices ni los CHECK.
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class SpringHouseApplicationTests {

	/** Base propia y desechable. Ver {@link TestDatabases}. */
	private static final String TEMPORARY_DATABASE = "spring_house_context_it";

	@DynamicPropertySource
	static void temporaryDatabase(DynamicPropertyRegistry registry) {
		String url = TestDatabases.provision(TEMPORARY_DATABASE);

		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", TestDatabases::username);
		registry.add("spring.datasource.password", TestDatabases::password);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		// Mismo tope que las demas pruebas de base de datos: el TestContext
		// cachea los contextos hasta que muere la JVM, y un pool sin acotar por
		// cada uno agota `max_connections` de PostgreSQL.
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
		registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
	}

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoads() {
	}

	/**
	 * La prueba que habria cazado el fallo original: se le pregunta a la
	 * CONEXION en que base esta, no a la configuracion.
	 *
	 * Mirar la propiedad {@code spring.datasource.url} no serviria: esa es
	 * justamente la que estaba bien escrita en `application.yml` y apuntaba a la
	 * base equivocada.
	 */
	@Test
	void theFullContextRunsOnTheIsolatedTestDatabase() throws SQLException {
		assertThat(connectedDatabase()).isEqualTo(TEMPORARY_DATABASE);
		assertThat(connectedDatabase()).isNotEqualTo("spring_house_db");
		assertThat(TestDatabases.isIsolated(connectedDatabase())).isTrue();
	}

	/**
	 * Y Flyway migro AHI, no en la base de desarrollo: el esquema completo
	 * existe dentro de la base desechable.
	 */
	@Test
	void flywayMigratedTheIsolatedDatabaseAndNotTheDevelopmentOne() throws SQLException {
		assertThat(scalar("SELECT COUNT(*) FROM flyway_schema_history"
				+ " WHERE success = true")).isPositive();
		// V37 incluida: la migracion del segundo PDF de cierre sigue aplicandose.
		assertThat(scalar("SELECT COUNT(*) FROM flyway_schema_history"
				+ " WHERE version = '37' AND success = true")).isEqualTo(1);
		assertThat(scalar("SELECT COUNT(*) FROM information_schema.columns"
				+ " WHERE table_name = 'cash_closure_item'"
				+ " AND column_name IN ('family_id', 'brand_id', 'model_id')"))
				.isEqualTo(3);
	}

	private String connectedDatabase() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT current_database()")) {
			rows.next();
			return rows.getString(1);
		}
	}

	private int scalar(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getInt(1);
		}
	}

}
