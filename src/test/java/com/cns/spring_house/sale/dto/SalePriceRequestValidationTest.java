package com.cns.spring_house.sale.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * E. Lo que nunca puede ser un precio se rechaza en la frontera (400), antes
 * de tocar ninguna venta: negativo, con mas de dos decimales, demasiado
 * grande o NaN. El minimo real (el costo) lo exige despues el handler.
 */
class SalePriceRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static AddSaleDetailRequestDTO line(BigDecimal price, BigDecimal seenCatalog) {
        return new AddSaleDetailRequestDTO(
                UUID.randomUUID(), null, null, 1, price, null, null, null, null, null, seenCatalog);
    }

    private static <T> Set<String> invalidFields(T request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void aWellFormedConfirmedPriceIsAccepted() {
        assertThat(invalidFields(line(new BigDecimal("340.50"), new BigDecimal("0.00")))).isEmpty();
        assertThat(invalidFields(line(new BigDecimal("340.50"), new BigDecimal("340.5")))).isEmpty();
    }

    /** I. Sin el catalogo que el vendedor tenia a la vista no hay linea: 400. */
    @Test
    void aNewLineWithoutTheSeenCatalogIsInvalid() {
        assertThat(invalidFields(line(new BigDecimal("340.50"), null))).containsExactly("expectedCatalogPrice");
        assertThat(invalidFields(line(null, null))).containsExactly("expectedCatalogPrice");
        assertThat(invalidFields(new RequestSpecificationDTO(
                UUID.randomUUID(), null, Map.of("largo", 90), 1, BigDecimal.ONE, null, null)))
                .containsExactly("expectedCatalogPrice");
    }

    @Test
    void aNegativeOrOverPrecisePriceIsRejected() {
        assertThat(invalidFields(line(new BigDecimal("-0.01"), BigDecimal.ZERO))).containsExactly("price");
        assertThat(invalidFields(line(new BigDecimal("10.001"), BigDecimal.ZERO))).containsExactly("price");
        assertThat(invalidFields(line(new BigDecimal("123456789.00"), BigDecimal.ZERO))).containsExactly("price");
    }

    @Test
    void theSeenCatalogPriceFollowsTheSameMoneyRules() {
        assertThat(invalidFields(line(BigDecimal.TEN, new BigDecimal("-1.00"))))
                .containsExactly("expectedCatalogPrice");
        assertThat(invalidFields(line(BigDecimal.TEN, new BigDecimal("1.005"))))
                .containsExactly("expectedCatalogPrice");
    }

    /** L. Una pieza pedida nunca llega sin precio ni a cero. */
    @Test
    void aRequestedSpecificationNeedsAPositivePrice() {
        Map<String, Object> attributes = Map.of("largo", 90);
        assertThat(invalidFields(new RequestSpecificationDTO(
                UUID.randomUUID(), null, attributes, 1, null, null, null, null, null, BigDecimal.ZERO)))
                .containsExactly("price");
        assertThat(invalidFields(new RequestSpecificationDTO(
                UUID.randomUUID(), null, attributes, 1, BigDecimal.ZERO, null, null, null, null, BigDecimal.ZERO)))
                .containsExactly("price");
        assertThat(invalidFields(new RequestSpecificationDTO(
                UUID.randomUUID(), null, attributes, 1, BigDecimal.ONE, null, null, null, null,
                new BigDecimal("-2")))).containsExactly("expectedCatalogPrice");
    }

    /**
     * El formato del precio nuevo. Q0.00 pasa el formato: si se admite lo
     * decide el servicio (solo con "sin cargo" expreso y con catalogo), igual
     * que al agregar la linea.
     */
    @Test
    void anEditedPriceFollowsTheMoneyRules() {
        assertThat(invalidFields(new UpdateRequestPriceDTO(BigDecimal.ZERO, BigDecimal.TEN, true))).isEmpty();
        assertThat(invalidFields(new UpdateRequestPriceDTO(new BigDecimal("-0.01"), BigDecimal.TEN)))
                .containsExactly("price");
        assertThat(invalidFields(new UpdateRequestPriceDTO(new BigDecimal("12.345"), BigDecimal.TEN)))
                .containsExactly("price");
        assertThat(invalidFields(new UpdateRequestPriceDTO(null, BigDecimal.TEN))).containsExactly("price");
    }

    /**
     * S. La escala de hasta dos decimales es formato valido (500, 500.0 y
     * 500.00 se comparan por valor en el servicio); tres decimales escritos,
     * aun en cero, son formato invalido como en cualquier campo de dinero.
     */
    @Test
    void theSeenPriceAcceptsAnyScaleUpToTwoDecimals() {
        assertThat(invalidFields(new UpdateRequestPriceDTO(new BigDecimal("500.0"), new BigDecimal("500")))).isEmpty();
        assertThat(invalidFields(new UpdateRequestPriceDTO(new BigDecimal("500"), new BigDecimal("500.00")))).isEmpty();
        assertThat(invalidFields(new UpdateRequestPriceDTO(new BigDecimal("500"), new BigDecimal("500.000"))))
                .containsExactly("expectedPrice");
    }

    /** P. Sin el precio que el vendedor tenia a la vista no hay edicion: 400. */
    @Test
    void anEditWithoutTheSeenPriceIsInvalid() {
        assertThat(invalidFields(new UpdateRequestPriceDTO(BigDecimal.TEN, null))).containsExactly("expectedPrice");
        assertThat(invalidFields(new UpdateRequestPriceDTO(BigDecimal.TEN, null, null))).containsExactly("expectedPrice");
    }

    /** El precio que el vendedor vio en la linea sigue las mismas reglas de dinero. */
    @Test
    void theSeenLinePriceFollowsTheMoneyRules() {
        assertThat(invalidFields(new UpdateRequestPriceDTO(BigDecimal.TEN, new BigDecimal("340.00")))).isEmpty();
        assertThat(invalidFields(new UpdateRequestPriceDTO(BigDecimal.TEN, new BigDecimal("-1"))))
                .containsExactly("expectedPrice");
        assertThat(invalidFields(new UpdateRequestPriceDTO(BigDecimal.TEN, new BigDecimal("1.001"))))
                .containsExactly("expectedPrice");
    }

    /** L. Tampoco al corregir el precio: NaN o infinito no llegan a ser una peticion. */
    @Test
    void notANumberIsNotAnEditedPrice() {
        JsonMapper mapper = JsonMapper.builder().build();
        for (String bad : List.of("\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"12,50\"")) {
            String body = "{\"price\":" + bad + ",\"expectedPrice\":500}";
            assertThatThrownBy(() -> mapper.readValue(body, UpdateRequestPriceDTO.class))
                    .as(body)
                    .isInstanceOf(tools.jackson.core.JacksonException.class);
        }
    }

    /** NaN no es un importe: el JSON no llega a ser una peticion. */
    @Test
    void notANumberIsNotAPrice() {
        JsonMapper mapper = JsonMapper.builder().build();
        String body = "{\"product\":\"" + UUID.randomUUID() + "\",\"quantity\":1,\"price\":\"NaN\"}";

        assertThatThrownBy(() -> mapper.readValue(body, AddSaleDetailRequestDTO.class))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }

}
