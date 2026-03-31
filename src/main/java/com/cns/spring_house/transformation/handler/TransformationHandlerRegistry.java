package com.cns.spring_house.transformation.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.shared.exception.ValidationException;

@Component
public class TransformationHandlerRegistry {
    
    private final Map<FamilyCategory, TransformationHandler> handlers;

    public TransformationHandlerRegistry(List<TransformationHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(
                        TransformationHandler::getType,
                        Function.identity()));
    }

    public TransformationHandler get(FamilyCategory category) {
        return Optional.ofNullable(handlers.get(category))
                .orElseThrow(() -> new ValidationException(
                        "No existe un manejador para la categoria: "
                        + category));
    }

}
