package com.cns.spring_house.sale.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.shared.exception.ValidationException;

@Component
public class SaleDetailHandlerRegistry {
    
    private final Map<FamilyCategory, SaleDetailHandler> handlers;

    public SaleDetailHandlerRegistry(List<SaleDetailHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(
                        SaleDetailHandler::getType,
                        Function.identity()));
    }

    public SaleDetailHandler get(FamilyCategory category) {
        return Optional.ofNullable(handlers.get(category))
                .orElseThrow(() -> new ValidationException(
                        "No existe un manejador para la categoria: "
                        + category));
    }

}
