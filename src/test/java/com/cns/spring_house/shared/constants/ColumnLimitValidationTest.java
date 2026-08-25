package com.cns.spring_house.shared.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cns.spring_house.brand.dto.CreateBrandRequestDTO;
import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;
import com.cns.spring_house.brand.dto.UpdateBrandRequestDTO;
import com.cns.spring_house.employee.dto.CreateEmployeeRequestDTO;
import com.cns.spring_house.family.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.family.dto.CreateFamilyRequestDTO;
import com.cns.spring_house.family.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.family.dto.UpdateFamilyRequestDTO;
import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.dto.CreateInventoryRequestDTO;
import com.cns.spring_house.job.dto.CreateJobRequestDTO;
import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.useraccount.dto.CreateUserAccountRequestDTO;
import com.cns.spring_house.workshop.dto.CreateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.UpdateWorkshopRequestDTO;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Los topes de longitud tienen que rechazarse aquí, en Bean Validation, y no en
 * PostgreSQL. Cuando una petición demasiado larga llegaba al INSERT, el driver
 * respondía "value too long for type character varying(25)", Spring lo envolvía
 * en DataIntegrityViolationException y el cliente recibía un 500 genérico por
 * lo que en realidad es un error de validación suyo.
 *
 * Cada campo se prueba en su frontera exacta: N caracteres se aceptan y N+1 se
 * rechazan. Los valores salen de {@link ColumnLimits}, la misma fuente que usan
 * las entidades, de modo que un cambio de columna que no actualice todo rompe
 * estas pruebas.
 */
class ColumnLimitValidationTest {

    /** El logo no participa en estas comprobaciones; solo satisface @NotNull. */
    private static final org.springframework.web.multipart.MultipartFile STUB_FILE =
            new org.springframework.mock.web.MockMultipartFile(
                    "logo", "logo.png", "image/png", new byte[] { 1 });

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

    private static String text(int length) {
        return "A".repeat(length);
    }

    private <T> Set<String> invalidFields(T dto) {
        Set<ConstraintViolation<T>> violations = validator.validate(dto);
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Nested
    @DisplayName("Customer: nit VARCHAR(13), name VARCHAR(75)")
    class CustomerLengths {

        private CreateCustomerRequestDTO create(String nit, String name) {
            return new CreateCustomerRequestDTO(nit, name);
        }

        private UpdateCustomerRequestDTO update(String nit, String name) {
            return new UpdateCustomerRequestDTO(nit, name);
        }

        /** Un NIT valido de longitud maxima: 13 digitos, no 13 letras. */
        private String maxNit() {
            return "1".repeat(ColumnLimits.CUSTOMER_NIT);
        }

        @Test
        void acceptsBothFieldsAtTheirLimit() {
            assertThat(invalidFields(create(maxNit(), text(ColumnLimits.CUSTOMER_NAME))))
                    .isEmpty();
        }

        @Test
        void rejectsANameOneCharacterTooLong() {
            assertThat(invalidFields(create(maxNit(), text(ColumnLimits.CUSTOMER_NAME + 1))))
                    .containsExactly("name");
        }

        @Test
        void rejectsANitLongerThanTheColumn() {
            assertThat(invalidFields(create("1".repeat(ColumnLimits.CUSTOMER_NIT + 1),
                    text(ColumnLimits.CUSTOMER_NAME))))
                    .contains("nit");
        }

        @Test
        void rejectsANitThatIsNotOnlyDigits() {
            assertThat(invalidFields(create("CF", text(10)))).contains("nit");
            assertThat(invalidFields(create("1234567A", text(10)))).contains("nit");
        }

        @Test
        void rejectsANitShorterThanEightDigits() {
            assertThat(invalidFields(create("1234567", text(10)))).contains("nit");
        }

        @Test
        void rejectsABlankName() {
            assertThat(invalidFields(create(maxNit(), "   "))).contains("name");
        }

        /** El PATCH es parcial: nulo en ambos campos no viola nada. */
        @Test
        void updateAcceptsAnEmptyPartialBody() {
            assertThat(invalidFields(update(null, null))).isEmpty();
        }

        /** null = "no lo cambies"; "   " = intento real de dejarlo vacio. */
        @Test
        void updateRejectsAWhitespaceOnlyNameButAcceptsNull() {
            assertThat(invalidFields(update(null, null))).isEmpty();
            assertThat(invalidFields(update(null, "   "))).containsExactly("name");
            assertThat(invalidFields(update(null, "\t\n"))).containsExactly("name");
            assertThat(invalidFields(update(null, "A"))).isEmpty();
        }

        /** El alta sigue exigiendo nombre: esta regla no la relaja. */
        @Test
        void createStillRequiresANonBlankName() {
            assertThat(invalidFields(create(maxNit(), null))).contains("name");
            assertThat(invalidFields(create(maxNit(), "   "))).contains("name");
        }

        @Test
        void updateAppliesTheSameLimits() {
            assertThat(invalidFields(update(maxNit(), text(ColumnLimits.CUSTOMER_NAME))))
                    .isEmpty();
            assertThat(invalidFields(update(maxNit(), text(ColumnLimits.CUSTOMER_NAME + 1))))
                    .containsExactly("name");
            assertThat(invalidFields(update("1234567", null))).contains("nit");
        }
    }

    @Nested
    @DisplayName("Product: sku VARCHAR(25), name VARCHAR(100)")
    class ProductLengths {

        private CreateProductRequestDTO create(String sku, String name) {
            return new CreateProductRequestDTO(
                    sku, name, UUID.randomUUID(), Map.of("largo", 200), null);
        }

        private UpdateProductRequestDTO update(String sku, String name) {
            return new UpdateProductRequestDTO(sku, name, null, null, null);
        }

        @Test
        void acceptsBothFieldsAtTheirLimit() {
            assertThat(invalidFields(create(
                    text(ColumnLimits.PRODUCT_SKU),
                    text(ColumnLimits.PRODUCT_NAME)))).isEmpty();
        }

        @Test
        void rejectsASkuOneCharacterTooLong() {
            assertThat(invalidFields(create(
                    text(ColumnLimits.PRODUCT_SKU + 1),
                    text(ColumnLimits.PRODUCT_NAME))))
                    .containsExactly("sku");
        }

        @Test
        void rejectsANameOneCharacterTooLong() {
            assertThat(invalidFields(create(
                    text(ColumnLimits.PRODUCT_SKU),
                    text(ColumnLimits.PRODUCT_NAME + 1))))
                    .containsExactly("name");
        }

        /** El PATCH es parcial: sin campos no hay nada que validar. */
        @Test
        void acceptsAPartialUpdateAtTheLimit() {
            assertThat(invalidFields(update(text(ColumnLimits.PRODUCT_SKU), null))).isEmpty();
            assertThat(invalidFields(update(null, text(ColumnLimits.PRODUCT_NAME)))).isEmpty();
            assertThat(invalidFields(update(null, null))).isEmpty();
        }

        @Test
        void rejectsAnUpdateOneCharacterTooLong() {
            assertThat(invalidFields(update(text(ColumnLimits.PRODUCT_SKU + 1), null)))
                    .containsExactly("sku");
            assertThat(invalidFields(update(null, text(ColumnLimits.PRODUCT_NAME + 1))))
                    .containsExactly("name");
        }
    }

    @Nested
    @DisplayName("Family: code VARCHAR(25), name VARCHAR(50)")
    class FamilyLengths {

        private CreateFamilyRequestDTO create(String code, String name) {
            return new CreateFamilyRequestDTO(code, name, false, "NORMAL");
        }

        @Test
        void acceptsBothFieldsAtTheirLimit() {
            assertThat(invalidFields(create(
                    text(ColumnLimits.FAMILY_CODE),
                    text(ColumnLimits.FAMILY_NAME)))).isEmpty();
        }

        @Test
        void rejectsACodeOneCharacterTooLong() {
            assertThat(invalidFields(create(
                    text(ColumnLimits.FAMILY_CODE + 1),
                    text(ColumnLimits.FAMILY_NAME))))
                    .containsExactly("code");
        }

        @Test
        void rejectsANameOneCharacterTooLong() {
            assertThat(invalidFields(create(
                    text(ColumnLimits.FAMILY_CODE),
                    text(ColumnLimits.FAMILY_NAME + 1))))
                    .containsExactly("name");
        }

        @Test
        void acceptsAPartialUpdateAtTheLimit() {
            assertThat(invalidFields(new UpdateFamilyRequestDTO(
                    text(ColumnLimits.FAMILY_CODE),
                    text(ColumnLimits.FAMILY_NAME),
                    null))).isEmpty();
        }

        @Test
        void rejectsAnUpdateOneCharacterTooLong() {
            assertThat(invalidFields(new UpdateFamilyRequestDTO(
                    text(ColumnLimits.FAMILY_CODE + 1), null, null)))
                    .containsExactly("code");
            assertThat(invalidFields(new UpdateFamilyRequestDTO(
                    null, text(ColumnLimits.FAMILY_NAME + 1), null)))
                    .containsExactly("name");
        }
    }

    @Nested
    @DisplayName("Attribute: name VARCHAR(50)")
    class AttributeLengths {

        @Test
        void acceptsANameAtTheLimit() {
            assertThat(invalidFields(new CreateAttributeRequestDTO(
                    text(ColumnLimits.ATTRIBUTE_NAME), "STRING", false))).isEmpty();
        }

        @Test
        void rejectsANameOneCharacterTooLong() {
            assertThat(invalidFields(new CreateAttributeRequestDTO(
                    text(ColumnLimits.ATTRIBUTE_NAME + 1), "STRING", false)))
                    .containsExactly("name");
        }

        @Test
        void acceptsAnUpdateAtTheLimit() {
            assertThat(invalidFields(new UpdateAttributeRequestDTO(
                    null, text(ColumnLimits.ATTRIBUTE_NAME), "STRING", false))).isEmpty();
        }

        @Test
        void rejectsAnUpdateOneCharacterTooLong() {
            assertThat(invalidFields(new UpdateAttributeRequestDTO(
                    null, text(ColumnLimits.ATTRIBUTE_NAME + 1), "STRING", false)))
                    .containsExactly("name");
        }
    }

    @Nested
    @DisplayName("Catálogo de vehículos: brand.name y model.name VARCHAR(50)")
    class CatalogLengths {

        @Test
        void acceptsABrandNameAtTheLimit() {
            assertThat(invalidFields(new CreateBrandRequestDTO(
                    text(ColumnLimits.BRAND_NAME), STUB_FILE))).isEmpty();
            assertThat(invalidFields(new UpdateBrandRequestDTO(
                    text(ColumnLimits.BRAND_NAME), null))).isEmpty();
        }

        @Test
        void rejectsABrandNameOneCharacterTooLong() {
            assertThat(invalidFields(new CreateBrandRequestDTO(
                    text(ColumnLimits.BRAND_NAME + 1), STUB_FILE)))
                    .containsExactly("name");
            assertThat(invalidFields(new UpdateBrandRequestDTO(
                    text(ColumnLimits.BRAND_NAME + 1), null)))
                    .containsExactly("name");
        }

        @Test
        void acceptsAModelNameAtTheLimit() {
            assertThat(invalidFields(new CreateModelRequestDTO(
                    UUID.randomUUID(), text(ColumnLimits.MODEL_NAME), 2024))).isEmpty();
        }

        @Test
        void rejectsAModelNameOneCharacterTooLong() {
            assertThat(invalidFields(new CreateModelRequestDTO(
                    UUID.randomUUID(), text(ColumnLimits.MODEL_NAME + 1), 2024)))
                    .containsExactly("name");
        }

        /** El CHECK de la columna arranca en 1900. */
        @Test
        void rejectsAYearBelowTheSchemaMinimum() {
            assertThat(invalidFields(new CreateModelRequestDTO(
                    UUID.randomUUID(), "Corolla", ColumnLimits.MODEL_YEAR_MIN - 1)))
                    .containsExactly("year");
            assertThat(invalidFields(new CreateModelRequestDTO(
                    UUID.randomUUID(), "Corolla", ColumnLimits.MODEL_YEAR_MIN))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Administración: workshop, employee y user_account")
    class AdminLengths {

        @Test
        void acceptsAWorkshopNameAtTheLimit() {
            assertThat(invalidFields(new CreateWorkshopRequestDTO(
                    text(ColumnLimits.WORKSHOP_NAME), "Zona 1"))).isEmpty();
        }

        @Test
        void rejectsAWorkshopNameOneCharacterTooLong() {
            assertThat(invalidFields(new CreateWorkshopRequestDTO(
                    text(ColumnLimits.WORKSHOP_NAME + 1), "Zona 1")))
                    .containsExactly("name");
            assertThat(invalidFields(new UpdateWorkshopRequestDTO(
                    text(ColumnLimits.WORKSHOP_NAME + 1), null, null)))
                    .containsExactly("name");
        }

        private CreateEmployeeRequestDTO employee(String firstName, String lastName) {
            return new CreateEmployeeRequestDTO(
                    UUID.randomUUID(), firstName, lastName,
                    text(ColumnLimits.EMPLOYEE_DPI), text(ColumnLimits.EMPLOYEE_PHONE),
                    LocalDate.of(1990, 1, 1));
        }

        @Test
        void acceptsEmployeeNamesAtTheLimit() {
            assertThat(invalidFields(employee(
                    text(ColumnLimits.EMPLOYEE_NAME), text(ColumnLimits.EMPLOYEE_NAME)))).isEmpty();
        }

        @Test
        void rejectsEmployeeNamesOneCharacterTooLong() {
            assertThat(invalidFields(employee(
                    text(ColumnLimits.EMPLOYEE_NAME + 1), "Garcia")))
                    .containsExactly("firstName");
            assertThat(invalidFields(employee(
                    "Juan", text(ColumnLimits.EMPLOYEE_NAME + 1))))
                    .containsExactly("lastName");
        }

        @Test
        void rejectsAUsernameOneCharacterTooLong() {
            assertThat(invalidFields(new CreateUserAccountRequestDTO(
                    UUID.randomUUID(), UUID.randomUUID(),
                    text(ColumnLimits.USERNAME + 1), "secreto")))
                    .containsExactly("username");
            assertThat(invalidFields(new CreateUserAccountRequestDTO(
                    UUID.randomUUID(), UUID.randomUUID(),
                    text(ColumnLimits.USERNAME), "secreto"))).isEmpty();
        }
    }

    /**
     * Los rangos numéricos también los impone el esquema: `NUMERIC(10, 2)` para
     * el dinero y `SMALLINT` para las existencias. Desbordarlos no daba un error
     * de validación sino un fallo del INSERT.
     */
    @Nested
    @DisplayName("Rangos numéricos: NUMERIC(10,2) y SMALLINT")
    class NumericBounds {

        private BigDecimal money(String value) {
            return new BigDecimal(value);
        }

        @Test
        void acceptsTheLargestAmountTheColumnHolds() {
            assertThat(invalidFields(new CreateInventoryRequestDTO(
                    UUID.randomUUID(), money("99999999.99"), money("99999999.99"), 0))).isEmpty();
        }

        @Test
        void rejectsAnAmountWithTooManyIntegerDigits() {
            assertThat(invalidFields(new CreateInventoryRequestDTO(
                    UUID.randomUUID(), money("100000000.00"), money("1.00"), 0)))
                    .containsExactly("price");
        }

        @Test
        void rejectsAnAmountWithTooManyDecimals() {
            assertThat(invalidFields(new CreateInventoryRequestDTO(
                    UUID.randomUUID(), money("1.001"), money("1.00"), 0)))
                    .containsExactly("price");
        }

        @Test
        void acceptsTheLargestStockTheColumnHolds() {
            assertThat(invalidFields(new CreateInventoryRequestDTO(
                    UUID.randomUUID(), money("1.00"), money("1.00"), ColumnLimits.SMALLINT_MAX)))
                    .isEmpty();
        }

        @Test
        void rejectsAStockBeyondSmallint() {
            assertThat(invalidFields(new CreateInventoryRequestDTO(
                    UUID.randomUUID(), money("1.00"), money("1.00"), ColumnLimits.SMALLINT_MAX + 1)))
                    .containsExactly("minStock");
        }

        @Test
        void rejectsUnitsBeyondSmallint() {
            assertThat(invalidFields(new AddStockRequestDTO(
                    ColumnLimits.SMALLINT_MAX + 1, null)))
                    .containsExactly("units");
            assertThat(invalidFields(new AddStockRequestDTO(
                    ColumnLimits.SMALLINT_MAX, null))).isEmpty();
        }

        /** Un precio de servicio en 0 es válido: no debe convertirse en error. */
        @Test
        void keepsZeroAValidPrice() {
            assertThat(invalidFields(new CreateJobRequestDTO(
                    UUID.randomUUID(), BigDecimal.ZERO))).isEmpty();
        }

        @Test
        void rejectsAJobPriceBeyondTheColumn() {
            assertThat(invalidFields(new CreateJobRequestDTO(
                    UUID.randomUUID(), money("100000000.00"))))
                    .containsExactly("price");
        }
    }

    /**
     * Los topes no son iguales entre sí: copiar uno a otro campo volvería a
     * abrir el hueco que provocaba el 500.
     */
    @Test
    void eachFieldKeepsItsOwnLimit() {
        assertThat(ColumnLimits.PRODUCT_SKU).isEqualTo(25);
        assertThat(ColumnLimits.PRODUCT_NAME).isEqualTo(100);
        assertThat(ColumnLimits.FAMILY_CODE).isEqualTo(25);
        assertThat(ColumnLimits.FAMILY_NAME).isEqualTo(50);
        assertThat(ColumnLimits.ATTRIBUTE_NAME).isEqualTo(50);
    }
}
