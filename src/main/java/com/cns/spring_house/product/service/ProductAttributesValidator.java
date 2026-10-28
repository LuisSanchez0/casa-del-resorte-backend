package com.cns.spring_house.product.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cns.spring_house.family.entity.Attribute;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.service.HoleMeasurementValidator;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * Lo que una pieza puede declarar como caracteristicas segun su familia: solo
 * atributos declarados, los obligatorios presentes, cada valor de su tipo y
 * las medidas con orificio coherentes.
 *
 * Es la regla del alta de productos, separada para que la solicitud de una
 * pieza que todavia no existe ("Solicitar con estas caracteristicas") no pueda
 * aceptar algo que el catalogo rechazaria.
 */
public final class ProductAttributesValidator {

    private ProductAttributesValidator() {
    }

    public static void validate(Family family, Map<String, Object> attributes) {
        Map<String, Attribute> allowed = family.getAttributes()
                .stream()
                .collect(Collectors.toMap(Attribute::getName, Function.identity()));
        Map<String, String> errors = new HashMap<>();
        attributes.forEach((key, value) -> {
            if (!allowed.containsKey(key)) {
                errors.put(key, "Atributo no permitido");
            }
        });
        allowed.forEach((key, attr) -> {
            Object value = attributes.get(key);
            if (value == null) {
                if (attr.getIsRequired()
                        && !HoleMeasurementValidator.ownsRequiredness(family, key)) {
                    errors.put(key, "Es obligatorio");
                }
            } else if (!attr.getDataType().isValid(value)) {
                errors.put(key, "Tipo inválido, se espera un " + attr.getDataType());
            }
        });
        if (!errors.isEmpty()) {
            throw new ValidationException("Error de validación", errors);
        }
        HoleMeasurementValidator.validate(family, attributes);
    }

}
