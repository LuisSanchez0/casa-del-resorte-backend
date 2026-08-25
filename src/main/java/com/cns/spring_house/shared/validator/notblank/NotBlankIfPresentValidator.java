package com.cns.spring_house.shared.validator.notblank;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NotBlankIfPresentValidator
        implements ConstraintValidator<NotBlankIfPresent, String> {

    /**
     * El null se acepta a proposito: en un PATCH significa "no cambies este
     * campo", no "dejalo vacio". Solo se rechaza lo que llega con contenido en
     * blanco, que es lo que el usuario creia estar escribiendo.
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return !value.isBlank();
    }

}
