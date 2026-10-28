package com.cns.spring_house.shared.config.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.dto.CreateInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.UpdateInventoryRequestDTO;
import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;
import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;

import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un campo entero deja de aceptar un número con decimales.
 *
 * Antes Jackson truncaba antes de que Bean Validation existiera: `2.5` se
 * convertía en `2`, `@Min`/`@Max` lo daban por bueno y la API respondía 201
 * habiendo guardado un valor que el cliente nunca pidió. No fallaba el INSERT,
 * que es lo que lo hacía difícil de ver.
 *
 * Se ejerce el bean de producción sobre un `JsonMapper.Builder`, igual que hace
 * Spring Boot al construir el mapper de la aplicación, y se deserializan los DTO
 * reales en vez de tipos de mentira.
 */
class StrictIntegerCoercionConfigTest {

    private final JsonMapper mapper = buildMapper();

    private static JsonMapper buildMapper() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new StrictIntegerCoercionConfig().strictIntegerCoercion().customize(builder);
        return builder.build();
    }

    private <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    @Nested
    @DisplayName("Inventario: minStock y units")
    class InventoryFields {

        @Test
        void acceptsAnIntegerToken() {
            assertThat(read("""
                    {"product":"6c1f2f4e-0000-0000-0000-000000000000",
                     "price":10.50,"cost":5,"minStock":2}
                    """, CreateInventoryRequestDTO.class).minStock()).isEqualTo(2);
        }

        @ParameterizedTest(name = "minStock = {0}")
        @ValueSource(strings = { "2.5", "-2.5", "0.1", "32767.5" })
        void rejectsAFractionalToken(String value) {
            assertThatThrownBy(() -> read("""
                    {"product":"6c1f2f4e-0000-0000-0000-000000000000",
                     "price":10,"cost":5,"minStock":%s}
                    """.formatted(value), CreateInventoryRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }

        @Test
        void rejectsAFractionalTokenOnUpdateToo() {
            assertThatThrownBy(() -> read(
                    "{\"minStock\":2.5}", UpdateInventoryRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }

        @Test
        void rejectsFractionalUnits() {
            assertThat(read("{\"units\":2}", AddStockRequestDTO.class).units()).isEqualTo(2);
            assertThatThrownBy(() -> read("{\"units\":2.5}", AddStockRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

    @Nested
    @DisplayName("Modelo: yearFrom y yearTo")
    class ModelYear {

        @Test
        void acceptsAnIntegerYearRange() {
            CreateModelRequestDTO request = read("""
                    {"brand":"6c1f2f4e-0000-0000-0000-000000000000",
                     "name":"Corolla","yearFrom":2020,"yearTo":2024}
                    """, CreateModelRequestDTO.class);
            assertThat(request.yearFrom()).isEqualTo(2020);
            assertThat(request.yearTo()).isEqualTo(2024);
        }

        /** El caso exacto que devolvía 201 guardando 2026. */
        @Test
        void rejectsAFractionalYear() {
            assertThatThrownBy(() -> read("""
                    {"brand":"6c1f2f4e-0000-0000-0000-000000000000",
                     "name":"Corolla","yearFrom":2026.5,"yearTo":2026.5}
                    """, CreateModelRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }

        /** Los dos extremos son enteros; el segundo no se cuela por ir aparte. */
        @Test
        void rejectsAFractionalUpperBound() {
            assertThatThrownBy(() -> read("""
                    {"brand":"6c1f2f4e-0000-0000-0000-000000000000",
                     "name":"Corolla","yearFrom":2020,"yearTo":2026.5}
                    """, CreateModelRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }

        @Test
        void rejectsAFractionalYearOnUpdateToo() {
            assertThatThrownBy(() -> read("{\"yearFrom\":2026.5}", UpdateModelRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
            assertThatThrownBy(() -> read("{\"yearTo\":2026.5}", UpdateModelRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

    @Nested
    @DisplayName("Venta y transformación")
    class OtherIntegerFields {

        @Test
        void rejectsAFractionalSaleQuantity() {
            assertThatThrownBy(() -> read("""
                    {"product":"6c1f2f4e-0000-0000-0000-000000000000","quantity":2.5}
                    """, AddSaleDetailRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }

        @Test
        void rejectsFractionalCurlCounts() {
            assertThatThrownBy(() -> read("""
                    {"inputItem":"6c1f2f4e-0000-0000-0000-000000000000","outputCurlCount":2.5}
                    """, CreateTransformationRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);

            assertThatThrownBy(() -> read("""
                    {"inputItem":"6c1f2f4e-0000-0000-0000-000000000000","remainderCurlCount":1.5}
                    """, CreateTransformationRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

    /**
     * Un `2.0` también se rechaza: el contrato del campo es entero y un token
     * flotante no lo respeta aunque no tenga parte fraccionaria. Aceptarlo
     * obligaría a distinguir "decimal íntegro" de "decimal con fracción" y
     * reabriría la puerta a la conversión silenciosa.
     *
     * Ningún cliente actual lo envía: JavaScript serializa `2.0` como `2`.
     */
    @Test
    void rejectsAnIntegralFloatToken() {
        assertThatThrownBy(() -> read("{\"units\":2.0}", AddStockRequestDTO.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    /** La política es solo para enteros: los importes conservan sus decimales. */
    @Test
    void leavesDecimalFieldsUntouched() {
        CreateInventoryRequestDTO dto = read("""
                {"product":"6c1f2f4e-0000-0000-0000-000000000000",
                 "price":10.12,"cost":5.05,"minStock":1}
                """, CreateInventoryRequestDTO.class);

        assertThat(dto.price()).isEqualByComparingTo(new BigDecimal("10.12"));
        assertThat(dto.cost()).isEqualByComparingTo(new BigDecimal("5.05"));
    }

    /**
     * Los campos enteros opcionales siguen admitiendo ausencia y null: la
     * política solo rechaza decimales, no convierte nada en obligatorio.
     */
    @Test
    void keepsOptionalIntegerFieldsOptional() {
        assertThatCode(() -> {
            assertThat(read("{}", UpdateInventoryRequestDTO.class).minStock()).isNull();
            assertThat(read("{\"minStock\":null}", UpdateInventoryRequestDTO.class).minStock())
                    .isNull();
        }).doesNotThrowAnyException();
    }

    /**
     * La coerción desde texto no se toca en esta tarea: el fallo confirmado era
     * el truncamiento silencioso de un token numérico. Se deja fijado el
     * comportamiento actual para que un cambio futuro sea deliberado.
     */
    @Test
    void documentsThatNumericStringsAreStillAccepted() {
        assertThat(read("{\"units\":\"2\"}", AddStockRequestDTO.class).units()).isEqualTo(2);
    }

    @Nested
    @DisplayName("Colochos pedidos y fabricados")
    class CurlCountFields {

        @Test
        void aWholeCountIsAccepted() {
            assertThat(read("{\"product\":\"00000000-0000-0000-0000-000000000001\",\"requestedCurls\":true,\"requestedCurlCount\":4}",
                    AddSaleDetailRequestDTO.class).requestedCurlCount()).isEqualTo(4);
        }

        @Test
        void aFractionalCountIsRejectedOnEveryPath() {
            assertThatThrownBy(() -> read(
                    "{\"product\":\"00000000-0000-0000-0000-000000000001\",\"requestedCurls\":true,\"requestedCurlCount\":3.5}",
                    AddSaleDetailRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
            assertThatThrownBy(() -> read(
                    "{\"family\":\"00000000-0000-0000-0000-000000000001\",\"requestedCurls\":true,\"requestedCurlCount\":3.5}",
                    com.cns.spring_house.sale.dto.RequestSpecificationDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
            assertThatThrownBy(() -> read(
                    "{\"inputItem\":\"00000000-0000-0000-0000-000000000001\",\"outputCurlCount\":3.5}",
                    com.cns.spring_house.sale.dto.FulfillTransformationRequestDTO.class))
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

}
