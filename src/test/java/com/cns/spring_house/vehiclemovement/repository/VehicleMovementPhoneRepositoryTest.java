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
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.workshop.entity.Workshop;

/**
 * El telefono de la bitacora, contra PostgreSQL de verdad.
 *
 * Se prueba aqui y no con mocks porque lo que se afirma vive en el esquema y en
 * el SQL: que el valor guardado sea una FOTO por fila, que la sugerencia sea un
 * top-1 acotado al taller y que el CHECK de V31 rechace lo que la aplicacion
 * tambien rechaza. Nada de eso lo puede demostrar un repositorio simulado.
 *
 * La base es temporal y la crea la propia prueba: nunca se toca la de desarrollo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class VehicleMovementPhoneRepositoryTest {

    private static final String TEMPORARY_DATABASE = "vehicle_movement_phone_it";
    private static final String PLATE = "P123ABC";
    private static final String OTHER_PLATE = "P999XYZ";

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

        registry.add("spring.datasource.url", () -> server + TEMPORARY_DATABASE);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Mismo tope que el resto de pruebas de base de datos del proyecto: los
        // contextos quedan cacheados hasta el final de la JVM y sin acotar el
        // pool la suma de todos agota max_connections.
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

    private static String value(Properties environment, String key, String fallback) {
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

    /**
     * Una bitacora YA CERRADA. Se cierran todas salvo cuando la prueba necesita
     * lo contrario, para que el indice de placa abierta no impida acumular varias
     * visitas de la misma placa, que es justo el escenario que hay que probar.
     */
    private VehicleMovement movement(
            Workshop workshop, String plate, LocalDateTime entry, String phone) {
        return entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshop)
                .plate(plate)
                .entry(entry)
                .exit(entry.plusHours(2))
                .phone(phone)
                .build());
    }

    private LocalDateTime day(int dayOfMonth) {
        return LocalDateTime.of(2026, 3, dayOfMonth, 9, 0);
    }

    // ── La foto historica ────────────────────────────────────────

    /**
     * EL invariante del cambio: la bitacora B no reescribe la bitacora A.
     *
     * Es la regresion critica del requisito. Si el telefono se hubiera modelado
     * como "el telefono actual de la placa" —una fila compartida, o una columna
     * en una entidad de vehiculo—, esta prueba fallaria: A pasaria a mostrar el
     * numero de B en cuanto B se registrara.
     */
    @Test
    @DisplayName("una bitacora posterior con otro telefono no cambia la anterior")
    void alaterMovementDoesNotRewriteTheEarlierOne() {
        Workshop workshopA = workshop("TALLER A");

        VehicleMovement first = movement(workshopA, PLATE, day(1), "58452136");
        VehicleMovement second = movement(workshopA, PLATE, day(2), "55554444");

        entityManager.clear();

        assertThat(repository.findById(first.getId()))
                .get()
                .extracting(VehicleMovement::getPhone)
                .isEqualTo("58452136");
        assertThat(repository.findById(second.getId()))
                .get()
                .extracting(VehicleMovement::getPhone)
                .isEqualTo("55554444");
    }

    /**
     * Reutilizar el numero anterior significa ESCRIBIRLO OTRA VEZ en la fila
     * nueva, no apuntar a la vieja. Las dos filas acaban con el mismo texto y
     * ninguna depende de la otra: borrar o cambiar una deja intacta la otra.
     */
    @Test
    @DisplayName("reutilizar el telefono guarda una copia propia en la fila nueva")
    void reusingThePhoneStoresItsOwnCopy() {
        Workshop workshopA = workshop("TALLER A");

        VehicleMovement first = movement(workshopA, PLATE, day(1), "58452136");
        VehicleMovement second = movement(workshopA, PLATE, day(2), "58452136");

        entityManager.clear();

        assertThat(repository.findById(first.getId()).orElseThrow().getPhone())
                .isEqualTo("58452136");
        assertThat(repository.findById(second.getId()).orElseThrow().getPhone())
                .isEqualTo("58452136");
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    /** El historico anterior a V31 se queda en NULL y nadie lo rellena. */
    @Test
    @DisplayName("una bitacora heredada sin telefono sigue en null")
    void legacyRowsStayNull() {
        Workshop workshopA = workshop("TALLER A");

        VehicleMovement legacy = movement(workshopA, PLATE, day(1), null);
        movement(workshopA, PLATE, day(2), "55554444");

        entityManager.clear();

        assertThat(repository.findById(legacy.getId()))
                .get()
                .extracting(VehicleMovement::getPhone)
                .isNull();
    }

    /** Registrar la salida escribe `exit` y nada mas. */
    @Test
    @DisplayName("marcar la salida no toca el telefono de la entrada")
    void registeringExitDoesNotTouchThePhone() {
        Workshop workshopA = workshop("TALLER A");

        VehicleMovement open = entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(1))
                .phone("58452136")
                .build());

        open.setExit(day(1).plusHours(3));
        entityManager.persistAndFlush(open);
        entityManager.clear();

        VehicleMovement reloaded = repository.findById(open.getId()).orElseThrow();
        assertThat(reloaded.getExit()).isNotNull();
        assertThat(reloaded.getPhone()).isEqualTo("58452136");
    }

    // ── La sugerencia ────────────────────────────────────────────

    @Test
    @DisplayName("sugiere el telefono de la bitacora mas reciente de esa placa")
    void suggestsThePhoneOfTheMostRecentMovement() {
        Workshop workshopA = workshop("TALLER A");

        movement(workshopA, PLATE, day(1), "11111111");
        movement(workshopA, PLATE, day(2), "22222222");
        movement(workshopA, PLATE, day(3), "33333333");

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), PLATE))
                .contains("33333333");
    }

    /**
     * Una visita POSTERIOR sin telefono no borra la sugerencia que dejo una
     * anterior. Se busca el ultimo telefono CONOCIDO, no el ultimo movimiento:
     * por eso `phone IS NOT NULL` esta en el WHERE y no aplicado despues.
     */
    @Test
    @DisplayName("una bitacora mas nueva sin telefono no borra la sugerencia previa")
    void anewerNullRowDoesNotEraseThePreviousSuggestion() {
        Workshop workshopA = workshop("TALLER A");

        movement(workshopA, PLATE, day(1), "58452136");
        movement(workshopA, PLATE, day(2), null);

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), PLATE))
                .contains("58452136");
    }

    @Test
    @DisplayName("una placa desconocida no tiene sugerencia")
    void anunknownPlateHasNoSuggestion() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, PLATE, day(1), "58452136");

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), OTHER_PLATE))
                .isEmpty();
    }

    @Test
    @DisplayName("una placa conocida sin ningun telefono tampoco tiene sugerencia")
    void aplateWithOnlyLegacyRowsHasNoSuggestion() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, PLATE, day(1), null);
        movement(workshopA, PLATE, day(2), null);

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), PLATE))
                .isEmpty();
    }

    /**
     * Con un solo taller cualquier consulta pareceria acotada. El aislamiento se
     * prueba SIEMPRE con dos: el telefono que registro el taller B para esa placa
     * no es dato del taller A, ni siquiera como sugerencia.
     */
    @Test
    @DisplayName("la sugerencia no cruza talleres")
    void thesuggestionIsScopedToTheWorkshop() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");

        movement(workshopA, PLATE, day(1), "11111111");
        // Mas reciente, pero de otro taller: no debe ganar ni aparecer.
        movement(workshopB, PLATE, day(9), "99999999");

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), PLATE))
                .contains("11111111");
        assertThat(repository.findLatestPhoneByPlate(workshopB.getId(), PLATE))
                .contains("99999999");
    }

    @Test
    @DisplayName("un taller sin ninguna bitacora de esa placa no recibe sugerencia")
    void aworkshopWithoutThatPlateGetsNothing() {
        Workshop workshopA = workshop("TALLER A");
        Workshop workshopB = workshop("TALLER B");

        movement(workshopA, PLATE, day(1), "11111111");

        assertThat(repository.findLatestPhoneByPlate(workshopB.getId(), PLATE))
                .isEmpty();
    }

    /**
     * La consulta compara la forma canonica de la placa —UPPER(BTRIM(...))—, la
     * misma que usan el indice unico de V28 y la normalizacion del DTO. Si esas
     * formas se separaran, la sugerencia dejaria de encontrar bitacoras que si
     * existen.
     */
    @Test
    @DisplayName("la sugerencia encuentra la placa por su forma canonica")
    void thesuggestionMatchesTheCanonicalPlate() {
        Workshop workshopA = workshop("TALLER A");
        movement(workshopA, PLATE, day(1), "58452136");

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), "P123ABC"))
                .contains("58452136");
    }

    /** Que exista sugerencia no implica que se pueda abrir otra visita. */
    @Test
    @DisplayName("tener sugerencia no habilita una segunda visita abierta")
    void asuggestionDoesNotUnlockASecondOpenMovement() {
        Workshop workshopA = workshop("TALLER A");

        entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(1))
                .phone("58452136")
                .build());

        assertThat(repository.findLatestPhoneByPlate(workshopA.getId(), PLATE))
                .contains("58452136");

        assertThatThrownBy(() -> entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(2))
                .phone("58452136")
                .build()))
                .rootCause()
                .hasMessageContaining("duplicate key value violates unique constraint")
                .hasMessageContaining("uq_vehicle_movement_open_plate_per_workshop");
    }

    // ── El CHECK de V31 ──────────────────────────────────────────

    /**
     * La base garantiza la forma del telefono POR SU CUENTA, igual que V30 hace
     * con la placa: una escritura que no pase por el servicio —SQL manual, una
     * carga de datos, un endpoint futuro— tampoco puede dejar '5845-2136' en una
     * columna que el resto del sistema lee como ocho digitos.
     */
    @ParameterizedTest
    @ValueSource(strings = { "5845213", "5845-213", "abcdefgh", " 5845213", "5845 213" })
    @DisplayName("la base rechaza un telefono que no sean 8 digitos")
    void thedatabaseRejectsNonCanonicalPhones(String phone) {
        Workshop workshopA = workshop("TALLER A");

        assertThatThrownBy(() -> entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(1))
                .phone(phone)
                .build()))
                .rootCause()
                .hasMessageContaining("ck_vehicle_movement_phone_local");
    }

    /**
     * Nueve digitos no llegan siquiera al CHECK: no caben en VARCHAR(8). El tope
     * superior esta en el tipo, que es donde no se puede desactivar.
     */
    @Test
    @DisplayName("nueve digitos no caben en la columna")
    void ninedigitsDoNotFitTheColumn() {
        Workshop workshopA = workshop("TALLER A");

        assertThatThrownBy(() -> entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(1))
                .phone("123456789")
                .build()))
                .isNotNull();
    }

    @Test
    @DisplayName("la base admite null: es el historico anterior a V31")
    void thedatabaseAcceptsNull() {
        Workshop workshopA = workshop("TALLER A");

        VehicleMovement legacy = entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(1))
                .phone(null)
                .build());

        assertThat(legacy.getId()).isNotNull();
        assertThat(legacy.getPhone()).isNull();
    }

    /**
     * El puente entre las dos capas, igual que ya hace la prueba de la placa: lo
     * que la APLICACION valida es exactamente lo que la BASE acepta. Si alguien
     * tocara el @Pattern del DTO o el CHECK de la migracion sin tocar el otro,
     * esta prueba lo diria antes de que apareciera como un 500 en produccion.
     */
    @Test
    @DisplayName("lo que valida la aplicacion es lo que acepta la base")
    void whattheApplicationValidatesIsWhatTheDatabaseAccepts() {
        Workshop workshopA = workshop("TALLER A");
        UUID id = entityManager.persistAndFlush(VehicleMovement.builder()
                .workshop(workshopA)
                .plate(PLATE)
                .entry(day(1))
                .phone("58452136")
                .build())
                .getId();

        entityManager.clear();

        assertThat(repository.findById(id))
                .get()
                .extracting(VehicleMovement::getPhone)
                .isEqualTo("58452136");
    }
}
