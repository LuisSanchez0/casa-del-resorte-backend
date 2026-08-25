package com.cns.spring_house.shared.validator.notblank;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Texto opcional que, si viene, no puede ser solo espacios.
 *
 * Existe por la diferencia que un PATCH necesita y que ni {@code @NotBlank} ni
 * {@code @Size} cubren:
 *
 * <ul>
 * <li>{@code null} es VALIDO: el campo no se toca (semantica parcial).</li>
 * <li>{@code "   "} es INVALIDO: se pidio cambiar el campo y lo enviado no es un
 * valor util.</li>
 * </ul>
 *
 * {@code @NotBlank} rechazaria tambien el null y convertiria cada PATCH en un
 * PUT encubierto; {@code @Size} da por bueno {@code "   "} porque mide longitud,
 * no contenido. Sin esta regla, un PATCH con espacios se guardaba tal cual y el
 * cliente se quedaba literalmente sin nombre.
 */
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBlankIfPresentValidator.class)
public @interface NotBlankIfPresent {

    String message() default "El valor no puede estar en blanco";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
