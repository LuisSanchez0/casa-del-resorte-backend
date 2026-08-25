package com.cns.spring_house.vehiclemovement.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * El backend es el validador AUTORITATIVO del telefono de la bitacora.
 *
 * Angular valida lo mismo, pero eso es solo el aviso inmediato: una llamada
 * directa a la API, o un cliente con un bug, no debe poder dejar '+50258452136'
 * ni '5845-2136' en una columna que el resto del sistema lee como ocho digitos.
 */
class VehicleMovementPhoneValidationTest {

    private static final String PLATE = "P123ABC";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<ConstraintViolation<CreateVehicleMovementRequestDTO>> validate(String phone) {
        return validator.validate(
                new CreateVehicleMovementRequestDTO(PLATE, phone, null, null));
    }

    private String messagesOf(String phone) {
        return validate(phone)
                .stream()
                .map(ConstraintViolation::getMessage)
                .reduce("", (accumulated, message) -> accumulated + message);
    }

    @Test
    @DisplayName("acepta un telefono local guatemalteco de 8 digitos")
    void acceptsEightDigits() {
        assertThat(validate("58452136")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "00000000", "12345678", "99999999", "02345678" })
    @DisplayName("acepta cualquier combinacion de 8 digitos, tambien con cero inicial")
    void acceptsAnyEightDigitCombination(String phone) {
        // No se comprueba que el numero exista ni que este asignado: eso no es
        // algo que este sistema pueda saber, y fingir que lo sabe seria peor que
        // no comprobarlo. El cero inicial importa: es justo lo que se perderia si
        // la columna fuera un entero.
        assertThat(validate(phone)).isEmpty();
    }

    @Test
    @DisplayName("rechaza 7 digitos")
    void rejectsSevenDigits() {
        assertThat(messagesOf("1234567"))
                .contains("El telefono debe tener exactamente 8 digitos");
    }

    @Test
    @DisplayName("rechaza 9 digitos")
    void rejectsNineDigits() {
        assertThat(messagesOf("123456789"))
                .contains("El telefono debe tener exactamente 8 digitos");
    }

    @ParameterizedTest
    @ValueSource(strings = { "abcdefgh", "5845213a", "5845-213", "телефоно" })
    @DisplayName("rechaza cualquier cosa que no sean digitos")
    void rejectsNonDigits(String phone) {
        assertThat(validate(phone)).isNotEmpty();
    }

    /**
     * Las formas que la gente escribe de verdad y que este campo NO admite.
     * Ninguna se "arregla" al vuelo: en el proyecto no existe una politica de
     * normalizacion de telefonos, y sin ella recortar el 502 de '50258452136'
     * seria adivinar que ese 502 es un prefijo de pais y no parte del numero.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "5845-2136",
            "5845 2136",
            "+50258452136",
            "50258452136",
            "(502) 5845-2136" })
    @DisplayName("rechaza guiones, espacios interiores y prefijo internacional")
    void rejectsFormattedOrInternationalNumbers(String phone) {
        assertThat(messagesOf(phone))
                .contains("El telefono debe tener exactamente 8 digitos");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t" })
    @DisplayName("el telefono en blanco produce el error de obligatoriedad")
    void rejectsBlankPhone(String phone) {
        assertThat(messagesOf(phone)).contains("El telefono es obligatorio");
    }

    @Test
    @DisplayName("el telefono ausente produce el error de obligatoriedad")
    void rejectsNullPhone() {
        assertThat(messagesOf(null)).contains("El telefono es obligatorio");
    }

    /**
     * El espacio EXTERIOR se recorta (un campo de formulario lo trae sin que
     * nadie lo haya escrito) y lo que queda se valida y se persiste tal cual.
     * Validar una cosa y guardar otra es justo lo que hace inutil un CHECK.
     */
    @Test
    @DisplayName("recorta el espacio exterior y conserva exactamente lo demas")
    void trimsOuterWhitespaceOnly() {
        CreateVehicleMovementRequestDTO request =
                new CreateVehicleMovementRequestDTO(PLATE, "  58452136  ", null, null);

        assertThat(request.phone()).isEqualTo("58452136");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("recortar el exterior no salva un numero con espacios dentro")
    void trimmingDoesNotRescueInnerWhitespace() {
        CreateVehicleMovementRequestDTO request =
                new CreateVehicleMovementRequestDTO(PLATE, "  5845 2136  ", null, null);

        assertThat(request.phone()).isEqualTo("5845 2136");
        assertThat(validator.validate(request)).isNotEmpty();
    }

    /** La placa y el telefono se validan por separado: cada error es el suyo. */
    @Test
    @DisplayName("un telefono valido no compensa una placa invalida")
    void phoneAndPlateAreValidatedIndependently() {
        Set<ConstraintViolation<CreateVehicleMovementRequestDTO>> violations =
                validator.validate(new CreateVehicleMovementRequestDTO(
                        "P", "58452136", null, null));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .contains("La placa no tiene un formato valido");
    }

    // ── Sugerencia ───────────────────────────────────────────────

    /**
     * El endpoint de sugerencia exige la placa COMPLETA. Sin eso aceptaria
     * prefijos y se convertiria en un buscador de telefonos por tanteo.
     */
    @ParameterizedTest
    @ValueSource(strings = { "P", "P12", "123ABC", "P1234ABC" })
    @DisplayName("la sugerencia rechaza placas incompletas o mal formadas")
    void suggestionRejectsPartialPlates(String plate) {
        assertThat(validator.validate(new PhoneSuggestionRequestDTO(plate))).isNotEmpty();
    }

    @Test
    @DisplayName("la sugerencia normaliza la placa igual que el alta")
    void suggestionNormalizesPlateLikeCreate() {
        PhoneSuggestionRequestDTO request = new PhoneSuggestionRequestDTO("  p123abc  ");

        assertThat(request.plate())
                .isEqualTo(new CreateVehicleMovementRequestDTO(
                        "  p123abc  ", "58452136", null, null).plate());
        assertThat(validator.validate(request)).isEmpty();
    }
}
