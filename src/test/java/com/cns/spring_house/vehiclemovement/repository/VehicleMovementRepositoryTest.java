package com.cns.spring_house.vehiclemovement.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * Ejecuta las consultas reales contra PostgreSQL sobre una base temporal creada
 * por la propia prueba. Nunca utiliza la base de desarrollo.
 *
 * Prueba el aislamiento donde de verdad ocurre —la sentencia SQL— y la garantia
 * del indice parcial de V18, que es lo unico que sostiene la regla de placa
 * abierta cuando dos peticiones concurrentes superan la comprobacion previa.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class VehicleMovementRepositoryTest {

    private static final String TEMPORARY_DATABASE = "vehicle_movement_it";
    private static final String PLATE = "P123ABC";

    @Autowired
    private VehicleMovementRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @DynamicPropertySource
    static void temporaryDatabase(DynamicPropertyRegistry registry)
            throws IOException, SQLException {
        Properties environment = loadEnvironment();
        String host = value(environment, "DB_HOST", "localhost");
        String port = value(environment, "DB_PORT", "5432");
        String username = value(environment, "DB_USER", "postgres");
        String password = value(environment, "DB_PASS", null);
        String server = "jdbc:postgresql://" + host + ":" + port + "/";

        try (Connection connection = DriverManager.getConnection(
                server + "postgres", username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP DATABASE IF EXISTS " + TEMPORARY_DATABASE
                    + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + TEMPORARY_DATABASE);
        }

        registry.add("spring.datasource.url",
                () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Cada clase con @DynamicPropertySource levanta su propio contexto, y
        // el TestContext los CACHEA hasta que acaba la JVM: cada pool que no
        // se acota se queda con las 10 conexiones por defecto de Hikari, y la
        // suma de todos agota max_connections de PostgreSQL. Mismo tope que ya
        // usan las demas pruebas de base de datos del proyecto.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    private static Properties loadEnvironment() throws IOException {
        Properties environment = new Properties();
        Path envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            try (InputStream input = Files.newInputStream(envFile)) {
                environment.load(input);
            }
        }
        return environment;
    }

    private static String value(
            Properties environment, String key, String fallback) {
        String fromSystem = System.getenv(key);
        if (fromSystem != null && !fromSystem.isBlank()) {
            return fromSystem;
        }
        return environment.getProperty(key, fallback);
    }

    private Workshop workshop(String name) {
        return entityManager.persistAndFlush(Workshop.builder()
                .name(name)
                .address("DIRECCION")
                .build());
    }

    private VehicleMovement movement(Workshop workshop, LocalDateTime exit) {
        return entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshop)
                .plate(PLATE)
                .exit(exit)
                .build());
    }

    /**
     * La busqueda se ejecuta contra PostgreSQL porque el fallo original estaba en
     * el SQL generado: se comparaba lower(plate) contra el texto crudo del cliente.
     */
    @Test
    void plateSearchIsCaseInsensitiveAndTolerantToSpaces() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, null);

        for (String term : List.of("P123", "p123", " p123 ", "P123ABC", "123ab")) {
            assertThat(repository.findAll(
                    VehicleMovementSpecs.plateContains(term)))
                    .as("busqueda por '%s'", term)
                    .hasSize(1);
        }
    }

    @Test
    void aBlankPlateDoesNotFilter() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        movement(workshopA, null);
        movement(workshopB, null);

        assertThat(repository.findAll(VehicleMovementSpecs.plateContains(null)))
                .hasSize(2);
        assertThat(repository.findAll(VehicleMovementSpecs.plateContains("   ")))
                .hasSize(2);
    }

    @Test
    void aPlateThatDoesNotMatchReturnsNothing() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, null);

        assertThat(repository.findAll(VehicleMovementSpecs.plateContains("XYZ")))
                .isEmpty();
    }

    @Test
    void findByIdAndWorkshopIdDoesNotReachAnotherWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        VehicleMovement a1 = movement(workshopA, null);
        VehicleMovement b1 = movement(workshopB, null);

        assertThat(repository.findByIdAndWorkshopId(a1.getId(), workshopA.getId()))
                .isPresent();
        // Mismo UUID valido, taller equivocado: la fila no es alcanzable.
        assertThat(repository.findByIdAndWorkshopId(b1.getId(), workshopA.getId()))
                .isEmpty();
        assertThat(repository.findByIdAndWorkshopId(a1.getId(), workshopB.getId()))
                .isEmpty();
    }

    @Test
    void openPlateIsDetectedOnlyInsideItsOwnWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        movement(workshopA, null);

        assertThat(repository.existsByPlateAndWorkshopIdAndExitIsNull(
                PLATE, workshopA.getId())).isTrue();
        // La entrada abierta de A no puede bloquear a B.
        assertThat(repository.existsByPlateAndWorkshopIdAndExitIsNull(
                PLATE, workshopB.getId())).isFalse();
    }

    @Test
    void aClosedMovementDoesNotBlockTheSamePlateAgain() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, LocalDateTime.now());

        assertThat(repository.existsByPlateAndWorkshopIdAndExitIsNull(
                PLATE, workshopA.getId())).isFalse();
    }

    /**
     * Garantia de base de datos: sin el indice parcial dos peticiones concurrentes
     * podrian insertar ambas tras pasar la comprobacion previa.
     */
    @Test
    void theDatabaseRejectsTwoOpenMovementsForThePlateInTheSameWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, null);

        assertThatThrownBy(() -> movement(workshopA, null))
                .rootCause()
                .hasMessageContaining("duplicate key value violates unique constraint")
                .hasMessageContaining("uq_vehicle_movement_open_plate_per_workshop");
    }

    @Test
    void theDatabaseAllowsTheSameOpenPlateInDifferentWorkshops() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");
        movement(workshopA, null);
        VehicleMovement b1 = movement(workshopB, null);

        assertThat(b1.getId()).isNotNull();
        assertThat(repository.findByIdAndWorkshopId(b1.getId(), workshopB.getId()))
                .isPresent();
    }

    @Test
    void theDatabaseAllowsSeveralClosedMovementsForThePlate() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, LocalDateTime.now().minusDays(2));
        movement(workshopA, LocalDateTime.now().minusDays(1));
        VehicleMovement open = movement(workshopA, null);

        assertThat(open.getId()).isNotNull();
        assertThat(repository.existsByPlateAndWorkshopIdAndExitIsNull(
                PLATE, workshopA.getId())).isTrue();
    }

    @Test
    void aMovementIdAloneNeverIdentifiesTheRecord() {
        Workshop workshopA = workshop("TALLER A");
        VehicleMovement a1 = movement(workshopA, null);
        UUID unknownWorkshop = UUID.randomUUID();

        assertThat(repository.findByIdAndWorkshopId(a1.getId(), unknownWorkshop))
                .isEmpty();
    }

    /**
     * El servicio traduce la violacion a un 400 identificandola por el nombre del
     * indice. Esta prueba fija ese nombre y la forma de la excepcion: si alguna
     * migracion los cambia, la traduccion dejaria de aplicarse en silencio y el
     * cliente volveria a recibir un 500.
     */
    @Test
    void theUniqueViolationExposesTheIndexNameUsedByTheService() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, null);

        assertThatThrownBy(() -> repository.saveAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause()
                .isInstanceOf(ConstraintViolationException.class)
                .extracting(cause -> ((ConstraintViolationException) cause)
                        .getConstraintName())
                .isEqualTo("uq_vehicle_movement_open_plate_per_workshop");
    }

    /**
     * Un valor NO CANONICO no puede ni siquiera guardarse.
     *
     * Este bloque cambio de dueño con V30. Antes comprobaba que estas variantes
     * no pudieran abrirse DOS VECES, y quien las rechazaba era el indice unico
     * sobre la placa canonicalizada. Ahora la base va un paso antes: la placa no
     * canonica no llega a existir, asi que la rechaza
     * `ck_vehicle_movement_plate_canonical` y ya no hace falta que exista una
     * primera fila con la que colisionar.
     *
     * Se afirma el nombre de la restriccion a proposito. Sin eso la prueba
     * pasaria igual por el motivo equivocado —el indice unico, la longitud de
     * columna o el NOT NULL—, y dejaria de demostrar lo que dice demostrar.
     *
     * Se escribe por el EntityManager y no por el servicio: eso es justo lo que
     * simula la escritura sin normalizar (SQL manual, una carga de datos, un
     * endpoint futuro) contra la que la restriccion tiene que defender.
     */
    @ParameterizedTest(name = "la placa no canonica [{0}] no se puede guardar")
    @ValueSource(strings = {
        "p123abc",          // minusculas
        " P123ABC",         // espacio inicial
        "P123ABC ",         // espacio final
        " P123ABC ",        // ambos
        "  p123abc  ",      // ambos, ademas en minusculas
        "\tP123ABC",        // tabulador
        "P123ABC\t",        // tabulador final
        "\nP123ABC",        // salto de linea
        "P123ABC\n",        // salto de linea final
        "P123ABC\r",        // retorno de carro
        "P",                // forma incompleta
        "P12ABC",           // faltan digitos
        "P1234ABC",         // sobran digitos
        "P123ABCD",         // sobran letras
        "ABC123P",          // orden invertido
        "P123-ABC",         // separador
        " ",                // solo espacio
    })
    void theDatabaseRejectsAnyPlateThatIsNotAlreadyCanonical(String nonCanonical) {
        Workshop workshopA = workshop("TALLER A");

        assertThatThrownBy(() -> entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(nonCanonical)
                .entry(LocalDateTime.now())
                .build()))
                .rootCause()
                .hasMessageContaining("ck_vehicle_movement_plate_canonical");
    }

    /**
     * El contrato de la base es el MISMO que el del DTO, no uno mas estrecho: la
     * restriccion no puede rechazar formatos que el backend acepta a proposito.
     */
    @ParameterizedTest(name = "la placa canonica [{0}] se guarda")
    @ValueSource(strings = { "P123ABC", "C123ABC", "M123ABC", "TC123ABC", "X999XXX" })
    void theDatabaseAcceptsEveryCanonicalPlateTheApplicationSupports(String canonical) {
        Workshop workshopA = workshop("TALLER A");

        VehicleMovement saved = entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(canonical)
                .entry(LocalDateTime.now())
                .build());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPlate()).isEqualTo(canonical);
    }

    /**
     * El puente entre las dos capas: lo que la APLICACION produce es exactamente
     * lo que la BASE acepta.
     *
     * Se parte de una entrada sucia real (" p123abc "), se la deja normalizar al
     * mismo DTO que usa el endpoint y se persiste su resultado. Si las dos
     * formas canonicas se separaran alguna vez —porque alguien tocara el patron,
     * el `trim`/`toUpperCase` o la restriccion—, esta prueba lo diria de
     * inmediato en lugar de dejar que el fallo apareciera en produccion como un
     * 500 al registrar una entrada.
     *
     * Comprueba tambien lo que pide el contrato de la API: el valor GUARDADO es
     * el canonico, no el que tecleo la persona.
     */
    @Test
    void whatTheApplicationNormalizesIsExactlyWhatTheDatabaseAccepts() {
        CreateVehicleMovementRequestDTO request =
                new CreateVehicleMovementRequestDTO("  p123abc  ", "58452136", null, null);
        assertThat(request.plate()).isEqualTo(PLATE);

        Workshop workshopA = workshop("TALLER A");
        VehicleMovement saved = entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(request.plate())
                .entry(LocalDateTime.now())
                .build());
        entityManager.clear();

        assertThat(repository.findByIdAndWorkshopId(saved.getId(), workshopA.getId()))
                .get()
                .extracting(VehicleMovement::getPlate)
                .isEqualTo("P123ABC");
    }

    /**
     * Con la placa ya canonica, quien impide la SEGUNDA entrada abierta sigue
     * siendo el indice parcial. Es la otra mitad del par: el CHECK dice como
     * debe guardarse una placa, el indice cuantas veces puede estar abierta.
     *
     * Esta prueba usa un duplicado CANONICO precisamente para que el CHECK no
     * pueda adelantarse y robarle el motivo del fallo.
     */
    @Test
    void aCanonicalDuplicateIsStillRejectedByThePartialUniqueIndex() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, null);

        assertThatThrownBy(() -> entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(LocalDateTime.now())
                .build()))
                .rootCause()
                .hasMessageContaining("duplicate key value violates unique constraint")
                .hasMessageContaining("uq_vehicle_movement_open_plate_per_workshop");
    }

}
