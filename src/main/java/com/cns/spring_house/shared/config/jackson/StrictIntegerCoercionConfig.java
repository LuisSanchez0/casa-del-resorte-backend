package com.cns.spring_house.shared.config.jackson;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Un campo declarado entero deja de aceptar un número con decimales.
 *
 * Por omisión Jackson truncaba el valor ANTES de que Bean Validation lo viera:
 * `{"minStock": 2.5}` se convertía en `2`, pasaba `@Min`/`@Max` sin objeciones y
 * la API respondía 201 habiendo guardado un dato que el cliente nunca pidió. No
 * era un 500 ni un fallo del INSERT —el valor llegaba ya convertido—, sino algo
 * peor: una respuesta de éxito que no se corresponde con lo persistido.
 *
 * La política se declara una sola vez sobre el tipo lógico entero, de modo que
 * cubre `Integer`, `Short`, `Long` y `BigInteger` en todos los DTO sin tener que
 * anotar campo por campo ni escribir deserializadores a medida.
 *
 * Se aplica sobre el `JsonMapper` que construye Spring Boot en lugar de sustituirlo,
 * así que los módulos, el manejo de fechas y la configuración de ProblemDetail
 * siguen intactos.
 *
 * Alcance: JSON. Los formularios multipart no se deserializan con Jackson, pero
 * hoy ninguno de sus DTO tiene campos enteros; los enteros que llegan como
 * parámetro de consulta los convierte Spring, que ya rechaza un decimal.
 */
@Configuration
public class StrictIntegerCoercionConfig {

    /**
     * `2` se acepta; `2.5`, `-2.5` y también `2.0` se rechazan.
     *
     * Un `2.0` se rechaza a propósito: el contrato del campo es entero y un
     * token flotante no lo respeta, aunque su valor no tenga parte fraccionaria.
     * Aceptarlo obligaría a distinguir "decimal íntegro" de "decimal con
     * fracción" y volvería a abrir la puerta a la conversión silenciosa. Ningún
     * cliente actual lo envía: JavaScript serializa `2.0` como `2`, así que el
     * frontend nunca produce ese token.
     */
    @Bean
    JsonMapperBuilderCustomizer strictIntegerCoercion() {
        return builder -> builder.withCoercionConfig(
                LogicalType.Integer,
                config -> config.setCoercion(
                        CoercionInputShape.Float,
                        CoercionAction.Fail));
    }

}
