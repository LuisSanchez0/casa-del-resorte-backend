    package com.cns.spring_house.inventory.handler;

    import java.util.List;
    import java.util.Map;
    import java.util.Optional;
    import java.util.function.Function;
    import java.util.stream.Collectors;

    import org.springframework.stereotype.Component;

    import com.cns.spring_house.family.entity.FamilyCategory;

    import jakarta.validation.ValidationException;

    @Component
    public class InventoryHandlerRegistry {

        private final Map<FamilyCategory, InventoryHandler> handlers;

        public InventoryHandlerRegistry(List<InventoryHandler> handlers) {
            this.handlers = handlers.stream()
                    .collect(Collectors.toMap(
                            InventoryHandler::getType,
                            Function.identity()));
        }

        public InventoryHandler get(FamilyCategory category) {
            return Optional.ofNullable(handlers.get(category))
                    .orElseThrow(() -> new ValidationException(
                            "No existe un manejador para la categoria: "
                            + category));
        }


    }
