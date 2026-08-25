package com.cns.spring_house.dailylog.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Los importes imposibles se rechazan en Bean Validation, antes de que el
 * servicio compare nada: un precio negativo o una cantidad cero son errores del
 * cliente y deben volver como 400 con el campo señalado, no como un CHECK
 * violado en PostgreSQL convertido en 500.
 *
 * La coherencia entre subtotal y precio por cantidad NO se comprueba aqui:
 * necesita los tres valores a la vez y vive en el servicio.
 */
class DailyLogLineValidationTest {

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

    private CreateDailyLogRequestDTO withProduct(
            String name, String unitPrice, Integer quantity, String subtotal) {
        return new CreateDailyLogRequestDTO(
                UUID.randomUUID(),
                List.of(new DailyLogProductRequestDTO(
                        name,
                        unitPrice == null ? null : new BigDecimal(unitPrice),
                        quantity,
                        subtotal == null ? null : new BigDecimal(subtotal))),
                List.of(),
                null);
    }

    private CreateDailyLogRequestDTO withService(
            String name, String unitPrice, Integer quantity, String subtotal) {
        return new CreateDailyLogRequestDTO(
                UUID.randomUUID(),
                List.of(),
                List.of(new DailyLogServiceRequestDTO(
                        name,
                        unitPrice == null ? null : new BigDecimal(unitPrice),
                        quantity,
                        subtotal == null ? null : new BigDecimal(subtotal))),
                null);
    }

    private Set<ConstraintViolation<CreateDailyLogRequestDTO>> validate(
            CreateDailyLogRequestDTO requestDTO) {
        return validator.validate(requestDTO);
    }

    @Test
    void aWellFormedLineHasNoViolations() {
        assertThat(validate(withProduct("BUJE", "75.00", 4, "300.00"))).isEmpty();
        assertThat(validate(withService("ALINEACION", "150.00", 1, "150.00"))).isEmpty();
    }

    @Test
    void aNegativeUnitPriceIsRejected() {
        assertThat(validate(withProduct("BUJE", "-1.00", 1, "-1.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("unitPrice"));
        assertThat(validate(withService("ALINEACION", "-1.00", 1, "-1.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("unitPrice"));
    }

    @Test
    void aQuantityBelowOneIsRejected() {
        assertThat(validate(withProduct("BUJE", "75.00", 0, "0.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("quantity"));
        assertThat(validate(withProduct("BUJE", "75.00", -3, "-225.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("quantity"));
        assertThat(validate(withService("ALINEACION", "150.00", 0, "0.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("quantity"));
    }

    @Test
    void aNegativeSubtotalIsRejected() {
        assertThat(validate(withProduct("BUJE", "75.00", 4, "-300.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("subtotal"));
        assertThat(validate(withService("ALINEACION", "150.00", 1, "-150.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("subtotal"));
    }

    @Test
    void aBlankNameIsRejected() {
        assertThat(validate(withProduct("   ", "75.00", 4, "300.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("name"));
        assertThat(validate(withService("   ", "150.00", 1, "150.00")))
                .anyMatch(violation -> violation.getPropertyPath()
                        .toString().endsWith("name"));
    }

    @Test
    void missingAmountsAreRejected() {
        assertThat(validate(withProduct("BUJE", null, null, null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .anySatisfy(path -> assertThat(path).endsWith("unitPrice"))
                .anySatisfy(path -> assertThat(path).endsWith("quantity"))
                .anySatisfy(path -> assertThat(path).endsWith("subtotal"));
    }

    @Test
    void theSaleIsMandatory() {
        CreateDailyLogRequestDTO withoutSale = new CreateDailyLogRequestDTO(
                null,
                List.of(new DailyLogProductRequestDTO(
                        "BUJE", new BigDecimal("75.00"), 4, new BigDecimal("300.00"))),
                List.of(),
                null);

        assertThat(validate(withoutSale))
                .anyMatch(violation -> "sale".equals(
                        violation.getPropertyPath().toString()));
    }

    /** La descripcion es opcional: un registro sin nota es valido. */
    @Test
    void theDescriptionIsOptional() {
        assertThat(validate(withProduct("BUJE", "75.00", 4, "300.00"))).isEmpty();
    }

}
