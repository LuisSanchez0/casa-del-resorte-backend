    package com.cns.spring_house.transformation.service;

    import java.time.LocalDateTime;
    import java.util.List;
    import java.util.Map;
    import java.util.UUID;

    import org.springframework.data.jpa.domain.Specification;
    import org.springframework.stereotype.Service;
    import org.springframework.transaction.annotation.Transactional;

    import com.cns.spring_house.employee.entity.Employee;
    import com.cns.spring_house.family.entity.FamilyCategory;
    import com.cns.spring_house.inventory.entity.InventoryItem;
    import com.cns.spring_house.inventory.entity.InventoryItemStatus;
    import com.cns.spring_house.inventory.repository.InventoryItemRepository;
    import com.cns.spring_house.shared.exception.EntityNotFoundException;
    import com.cns.spring_house.shared.exception.ValidationException;
    import com.cns.spring_house.shared.security.SecurityUtils;
    import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
    import com.cns.spring_house.transformation.dto.SearchTransformationRequestDTO;
    import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
    import com.cns.spring_house.transformation.entity.Transformation;
    import com.cns.spring_house.transformation.entity.TransformationItemRole;
    import com.cns.spring_house.transformation.handler.TransformationHandlerRegistry;
    import com.cns.spring_house.transformation.handler.TransformationHandler;
    import com.cns.spring_house.transformation.mapper.TransformationMapper;
    import com.cns.spring_house.transformation.repository.TransformationRepository;
    import com.cns.spring_house.transformation.repository.TransformationSpecs;
    import com.cns.spring_house.useraccount.entity.UserAccount;
    import com.cns.spring_house.useraccount.repository.UserAccountRepository;
    import com.cns.spring_house.workresult.service.WorkResultService;
    import com.cns.spring_house.workshop.entity.Workshop;

    import lombok.RequiredArgsConstructor;

    @Service
    @Transactional
    @RequiredArgsConstructor
    public class TransformationServiceImpl implements TransformationService {

        /** Debe coincidir con el atributo declarado en la familia. */
        private static final String CURL_COUNT_ATTRIBUTE = "cantidadColochos";

        private final TransformationMapper mapper;
        private final TransformationRepository repository;
        private final InventoryItemRepository itemRepository;
        private final UserAccountRepository userAccountRepository;
        private final TransformationHandlerRegistry registry;
        private final WorkResultService workResultService;

        @Override
        public List<TransformationResponseDTO> getAll(SearchTransformationRequestDTO requestDTO) {
            Specification<Transformation> specs = Specification.allOf(
                    TransformationSpecs.hasEmployee(requestDTO.employee()),
                    TransformationSpecs.hasWorkshop(requestDTO.workshop()),
                    TransformationSpecs.performedDateBetween(requestDTO.minDate(), requestDTO.maxDate()));
            List<TransformationResponseDTO> transformations = repository.findAll(specs)
                    .stream()
                    .map(mapper::toResponse)
                    .toList();
            return transformations;
        }

        @Override
        public TransformationResponseDTO get(UUID id) {
            Transformation transformation = repository.findById(id)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe la transformacion solicitada"));
            return mapper.toResponse(transformation);
        }

        @Override
        public TransformationResponseDTO create(
                CreateTransformationRequestDTO requestDTO) {
            InventoryItem inputItem = itemRepository
                    .findById(requestDTO.inputItem())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el item especificado"));
            if (!isProcessable(inputItem)) {
                throw new ValidationException(
                        "El item no esta disponible");
            }
            FamilyCategory category = getCategory(inputItem);
            if (category != FamilyCategory.CONTAINER
                    && requestDTO.containedItems() != null
                    && !requestDTO.containedItems().isEmpty()) {
                throw new ValidationException(
                        "Solo un contenedor admite liberar componentes seleccionados");
            }
            if (category == FamilyCategory.SPECIAL
                    && requestDTO.outputProduct() == null
                    && requestDTO.outputCurlCount() == null
                    && (requestDTO.outputAttributes() == null
                            || requestDTO.outputAttributes().isEmpty())) {
                throw new ValidationException(
                        "El producto de salida es obligatorio");
            }
            validateCurlCountScope(category, requestDTO);
            validateOutputMode(category, requestDTO);
            TransformationHandler handler = getHandler(inputItem);
            Transformation transformation = startTransformation();
            handler.generate(transformation, inputItem, requestDTO);
            transformation = repository.save(transformation);
            transformation.getItems().stream()
                    .filter(item -> item.getRole() == TransformationItemRole.OUTPUT)
                    .forEach(item -> workResultService
                            .createPendingFromItem(item.getItem()));
            return mapper.toResponse(transformation);
        }

        /**
         * Los colochos solo existen en las piezas SPECIAL. Llegan a un desarmado de
         * contenedor solo si el cliente construyó mal la petición, y aceptarlos en
         * silencio la daría por buena sin que nada los aplique.
         *
         * Dentro de SPECIAL, `cantidadColochos` tampoco puede viajar en
         * `outputAttributes`: la fuente oficial es `outputCurlCount` y admitir las dos
         * permitiría enviar dos cantidades distintas para la misma pieza.
         */
        private void validateCurlCountScope(
                FamilyCategory category, CreateTransformationRequestDTO requestDTO) {
            if (category != FamilyCategory.SPECIAL
                    && (requestDTO.outputCurlCount() != null
                            || requestDTO.remainderCurlCount() != null)) {
                throw new ValidationException(
                        "Solo una pieza especial admite configurar colochos");
            }
            Map<String, Object> outputAttributes = requestDTO.outputAttributes();
            if (outputAttributes == null) {
                return;
            }
            boolean carriesCurlCount = outputAttributes.keySet().stream()
                    .anyMatch(key -> CURL_COUNT_ATTRIBUTE.equalsIgnoreCase(key));
            if (carriesCurlCount) {
                throw new ValidationException(
                        "La cantidad de colochos se indica en outputCurlCount, no dentro de outputAttributes");
            }
        }

        /**
         * Los dos modos de definir la pieza de salida son excluyentes: o se elige
         * un producto existente —del que se toman sus características— o se
         * describen las características a mano. Enviar ambos no tiene una lectura
         * única, y hasta ahora `outputAttributes` se ignoraba en silencio cuando
         * llegaba junto a `outputProduct`, de modo que el cliente creía haber
         * pedido un cambio que nunca se aplicaba.
         *
         * `outputCurlCount` queda fuera de la exclusión a propósito: manda sobre
         * los dos modos y es obligatorio en las familias que declaran colochos.
         */
        private void validateOutputMode(
                FamilyCategory category, CreateTransformationRequestDTO requestDTO) {
            if (category != FamilyCategory.SPECIAL
                    || requestDTO.outputProduct() == null) {
                return;
            }
            Map<String, Object> outputAttributes = requestDTO.outputAttributes();
            if (outputAttributes != null && !outputAttributes.isEmpty()) {
                throw new ValidationException(
                        "Indica el producto de salida o las características de la pieza resultante, no ambos");
            }
        }

        private TransformationHandler getHandler(InventoryItem inputItem) {
            return registry.get(getCategory(inputItem));
        }

        private FamilyCategory getCategory(InventoryItem inputItem) {
            return inputItem.getInventory().getProduct().getFamily().getCategory();
        }

        /**
         * Normalmente solo se procesa una unidad AVAILABLE. La excepción es un
         * contenedor a medio desarmar: queda UNAVAILABLE porque ya no es vendible,
         * pero todavía guarda componentes y debe poder seguir liberándolos. Un
         * componente suelto dentro de una maleta también está UNAVAILABLE, pero no
         * contiene nada, así que sigue quedando fuera.
         */
        private boolean isProcessable(InventoryItem inputItem) {
            if (inputItem.getStatus() == InventoryItemStatus.AVAILABLE) {
                return true;
            }
            return inputItem.getStatus() == InventoryItemStatus.UNAVAILABLE
                    && getCategory(inputItem) == FamilyCategory.CONTAINER
                    && !inputItem.getContainedItems().isEmpty();
        }

        /**
         * El subject del JWT es UserAccount.id (ver JwtServiceImpl), no Employee.id.
         * El empleado y el taller se resuelven recorriendo la relación real
         * UserAccount -> Employee -> Workshop; buscar un Employee por el id de la
         * cuenta hacía fallar siempre la creación con "El empleado especificado no
         * existe", porque ambos identificadores son distintos.
         */
        private Transformation startTransformation() {
            UserAccount userAccount = userAccountRepository
                    .findById(SecurityUtils.getId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No existe el usuario autenticado"));
            Employee employee = userAccount.getEmployee();
            if (employee == null) {
                throw new ValidationException(
                        "El usuario autenticado no tiene un empleado asociado");
            }
            Workshop workshop = employee.getWorkshop();
            if (workshop == null) {
                throw new ValidationException(
                        "El empleado autenticado no tiene un taller asociado");
            }
            Transformation transformation = Transformation.builder()
                    .employee(employee)
                    .workshop(workshop)
                    .performedDate(LocalDateTime.now())
                    .build();
            return transformation;
        }

    }
