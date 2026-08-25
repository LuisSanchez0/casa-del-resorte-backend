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
 * El backend es el validador autoritativo de la placa: un cliente con un bug (o
 * una llamada directa a la API) no debe poder registrar una placa incompleta
 * como "P" solo porque Angular no lo impidio.
 */
class CreateVehicleMovementRequestDTOTest {

    /** Telefono valido, para que la placa sea lo unico que se este probando. */
    private static final String PHONE = "58452136";

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

    private Set<ConstraintViolation<CreateVehicleMovementRequestDTO>> validate(String plate) {
        return validator.validate(
                new CreateVehicleMovementRequestDTO(plate, PHONE, null, null));
    }

    private String messagesOf(String plate) {
        return validate(plate)
                .stream()
                .map(ConstraintViolation::getMessage)
                .reduce("", (accumulated, message) -> accumulated + message);
    }

    @ParameterizedTest
    @ValueSource(strings = { "P123ABC", "P122ABC", "C456XYZ", "M789DEF", "A100BCD", "TC123GHI" })
    @DisplayName("acepta los prefijos guatemaltecos soportados")
    void acceptsSupportedPlates(String plate) {
        assertThat(validate(plate)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "P", "P1", "P12ABC", "123ABC", "PABC123", "P1234ABC", "P123AB" })
    @DisplayName("rechaza placas incompletas o con otra estructura")
    void rejectsMalformedPlates(String plate) {
        assertThat(validate(plate)).isNotEmpty();
        assertThat(messagesOf(plate)).contains("Formato esperado: P123ABC");
    }

    @Test
    @DisplayName("una placa de un solo caracter se rechaza aunque no este en blanco")
    void rejectsSingleCharacterPlate() {
        assertThat(validate("P")).hasSize(1);
    }

    @Test
    @DisplayName("la normalizacion sigue intacta: se recorta y se pasa a mayusculas")
    void keepsNormalization() {
        CreateVehicleMovementRequestDTO request = new CreateVehicleMovementRequestDTO(
                "  p122abc  ", PHONE, null, null);

        assertThat(request.plate()).isEqualTo("P122ABC");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("la placa en blanco sigue produciendo el error de obligatoriedad")
    void rejectsBlankPlate() {
        assertThat(messagesOf("   ")).contains("La placa es obligatoria");
        assertThat(messagesOf(null)).contains("La placa es obligatoria");
    }
}
