package com.cns.spring_house.transformation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.family.entity.Family;
import com.cns.spring_house.family.entity.FamilyCategory;
import com.cns.spring_house.inventory.entity.Inventory;
import com.cns.spring_house.inventory.entity.InventoryItem;
import com.cns.spring_house.inventory.entity.InventoryItemStatus;
import com.cns.spring_house.inventory.repository.InventoryItemRepository;
import com.cns.spring_house.product.entity.Product;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.entity.Transformation;
import com.cns.spring_house.transformation.entity.TransformationItem;
import com.cns.spring_house.transformation.entity.TransformationItemRole;
import com.cns.spring_house.transformation.handler.TransformationHandler;
import com.cns.spring_house.transformation.handler.TransformationHandlerRegistry;
import com.cns.spring_house.transformation.mapper.TransformationMapper;
import com.cns.spring_house.transformation.repository.TransformationRepository;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.useraccount.repository.UserAccountRepository;
import com.cns.spring_house.workresult.service.WorkResultService;
import com.cns.spring_house.workshop.entity.Workshop;

@ExtendWith(MockitoExtension.class)
class TransformationServiceImplTest {

    @Mock
    private TransformationMapper mapper;

    @Mock
    private TransformationRepository repository;

    @Mock
    private InventoryItemRepository itemRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private TransformationHandlerRegistry registry;

    @Mock
    private TransformationHandler handler;

    @Mock
    private WorkResultService workResultService;

    @InjectMocks
    private TransformationServiceImpl service;

    /**
     * JwtServiceImpl firma el token con UserAccount.id como subject, por lo que
     * este id NUNCA coincide con Employee.id. Mantenerlos distintos en el test es
     * lo que impide que vuelva a colarse la confusión entre ambos.
     */
    private final UUID userAccountId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();
    private final UUID workshopId = UUID.randomUUID();
    private final Workshop workshop = Workshop.builder()
            .id(workshopId)
            .build();
    private final Employee employee = Employee.builder()
            .id(employeeId)
            .workshop(workshop)
            .build();

    private UserAccount userAccountWith(Employee associatedEmployee) {
        return UserAccount.builder()
                .id(userAccountId)
                .employee(associatedEmployee)
                .build();
    }

    @BeforeEach
    void setUpAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userAccountId.toString())
                .claim("workshop", workshopId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private InventoryItem item(FamilyCategory category) {
        return InventoryItem.builder()
                .id(UUID.randomUUID())
                .status(InventoryItemStatus.AVAILABLE)
                .inventory(Inventory.builder()
                        .workshop(workshop)
                        .product(Product.builder()
                                .id(UUID.randomUUID())
                                .family(Family.builder()
                                        .category(category)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    @Test
    void createRegistersOnlyTheOutputItemAsPendingWork() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        InventoryItem outputItem = item(FamilyCategory.SPECIAL);
        InventoryItem remainderItem = item(FamilyCategory.SPECIAL);
        UUID outputProductId = UUID.randomUUID();

        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));
        when(registry.get(FamilyCategory.SPECIAL)).thenReturn(handler);
        when(userAccountRepository.findById(userAccountId))
                .thenReturn(Optional.of(userAccountWith(employee)));
        doAnswer(invocation -> {
            Transformation transformation = invocation.getArgument(0);
            transformation.addItem(TransformationItem.builder()
                    .item(outputItem)
                    .role(TransformationItemRole.OUTPUT)
                    .build());
            transformation.addItem(TransformationItem.builder()
                    .item(remainderItem)
                    .role(TransformationItemRole.REMAINDER)
                    .build());
            transformation.addItem(TransformationItem.builder()
                    .item(inputItem)
                    .role(TransformationItemRole.INPUT)
                    .build());
            return null;
        }).when(handler).generate(any(), any(), any());
        when(repository.save(any(Transformation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateTransformationRequestDTO(
                inputItem.getId(), null, outputProductId, null, null, null, null, null));

        verify(workResultService).createPendingFromItem(outputItem);
        verify(workResultService, never()).createPendingFromItem(inputItem);
        verify(workResultService, never())
                .createPendingFromItem(remainderItem);
    }

    /**
     * Los colochos solo existen en piezas SPECIAL: si llegan a un desarmado de
     * contenedor, la peticion esta mal construida y nada los aplicaria.
     */
    @Test
    void createRejectsCurlCountsOnANonSpecialCategory() {
        InventoryItem inputItem = item(FamilyCategory.CONTAINER);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, null, null, null, 2, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo una pieza especial admite configurar colochos");

        verify(workResultService, never()).createPendingFromItem(any());
    }

    @Test
    void createRejectsRemainderCurlCountOnANonSpecialCategory() {
        InventoryItem inputItem = item(FamilyCategory.CONTAINER);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, null, null, null, null, 1, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Solo una pieza especial admite configurar colochos");
    }

    /**
     * Los dos modos de definir la salida son excluyentes. Antes las
     * caracteristicas se ignoraban en silencio cuando venia un producto elegido,
     * asi que el cliente creia haber pedido un cambio que nunca se aplicaba.
     */
    @Test
    void createRejectsOutputAttributesTogetherWithAnOutputProduct() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, UUID.randomUUID(), null,
                        Map.of("largo", 900), 0, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Indica el producto de salida o las características de la pieza resultante, no ambos");

        verify(workResultService, never()).createPendingFromItem(any());
    }

    /** La cantidad de colochos tiene una sola fuente: outputCurlCount. */
    @Test
    void createRejectsCantidadColochosInsideOutputAttributes() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, null, null,
                        Map.of("cantidadColochos", 2), null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La cantidad de colochos se indica en outputCurlCount, no dentro de outputAttributes");

        verify(workResultService, never()).createPendingFromItem(any());
    }

    @Test
    void createRejectsCantidadColochosWrittenInAnotherCase() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, null, null,
                        Map.of("CANTIDADCOLOCHOS", 2), null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("La cantidad de colochos se indica en outputCurlCount, no dentro de outputAttributes");
    }

    /** outputCurlCount por si solo ya describe la pieza pedida. */
    @Test
    void createAcceptsOnlyTheCurlCountAsTheRequestedChange() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));
        when(registry.get(FamilyCategory.SPECIAL)).thenReturn(handler);
        when(userAccountRepository.findById(userAccountId))
                .thenReturn(Optional.of(userAccountWith(employee)));
        when(repository.save(any(Transformation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateTransformationRequestDTO(
                inputItem.getId(), null, null, null, null, 2, null, null));

        verify(handler).generate(any(), any(), any());
    }

    @Test
    void createRejectsASpecialTransformationWithoutAnyRequestedChange() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, null, null, null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("El producto de salida es obligatorio");
    }

    @Test
    void createRejectsAnItemThatIsNotAvailable() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        inputItem.setStatus(InventoryItemStatus.SOLD);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, UUID.randomUUID(), null, null, null, null, null)))
                .isInstanceOf(ValidationException.class);

        verify(workResultService, never()).createPendingFromItem(any());
    }

    @Test
    void createResolvesTheEmployeeThroughTheAuthenticatedUserAccount() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));
        when(registry.get(FamilyCategory.SPECIAL)).thenReturn(handler);
        when(userAccountRepository.findById(userAccountId))
                .thenReturn(Optional.of(userAccountWith(employee)));
        when(repository.save(any(Transformation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateTransformationRequestDTO(
                inputItem.getId(), null, UUID.randomUUID(), null, null, null, null, null));

        ArgumentCaptor<Transformation> saved =
                ArgumentCaptor.forClass(Transformation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmployee()).isSameAs(employee);
        assertThat(saved.getValue().getEmployee().getId()).isEqualTo(employeeId);
        // El id de la cuenta jamás debe usarse como si fuera el del empleado.
        assertThat(saved.getValue().getEmployee().getId())
                .isNotEqualTo(userAccountId);
    }

    @Test
    void createTakesTheWorkshopFromTheAuthenticatedEmployee() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));
        when(registry.get(FamilyCategory.SPECIAL)).thenReturn(handler);
        when(userAccountRepository.findById(userAccountId))
                .thenReturn(Optional.of(userAccountWith(employee)));
        when(repository.save(any(Transformation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateTransformationRequestDTO(
                inputItem.getId(), null, UUID.randomUUID(), null, null, null, null, null));

        ArgumentCaptor<Transformation> saved =
                ArgumentCaptor.forClass(Transformation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getWorkshop()).isSameAs(workshop);
        assertThat(saved.getValue().getWorkshop().getId()).isEqualTo(workshopId);
    }

    @Test
    void createRejectsAnAuthenticatedUserWithoutAnEmployee() {
        InventoryItem inputItem = item(FamilyCategory.SPECIAL);
        when(itemRepository.findById(inputItem.getId()))
                .thenReturn(Optional.of(inputItem));
        when(registry.get(FamilyCategory.SPECIAL)).thenReturn(handler);
        when(userAccountRepository.findById(userAccountId))
                .thenReturn(Optional.of(userAccountWith(null)));

        assertThatThrownBy(() -> service.create(
                new CreateTransformationRequestDTO(
                        inputItem.getId(), null, UUID.randomUUID(), null, null, null, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no tiene un empleado asociado");

        verify(repository, never()).save(any());
        verify(workResultService, never()).createPendingFromItem(any());
    }

    @Test
    void createDoesNotRegisterPendingWorkForReleasedComponents() {
        InventoryItem containerItem = item(FamilyCategory.CONTAINER);
        InventoryItem componentA = item(FamilyCategory.SPECIAL);
        InventoryItem componentB = item(FamilyCategory.SPECIAL);

        when(itemRepository.findById(containerItem.getId()))
                .thenReturn(Optional.of(containerItem));
        when(registry.get(FamilyCategory.CONTAINER)).thenReturn(handler);
        when(userAccountRepository.findById(userAccountId))
                .thenReturn(Optional.of(userAccountWith(employee)));
        doAnswer(invocation -> {
            Transformation transformation = invocation.getArgument(0);
            transformation.addItem(TransformationItem.builder()
                    .item(componentA)
                    .role(TransformationItemRole.RELEASED)
                    .build());
            transformation.addItem(TransformationItem.builder()
                    .item(componentB)
                    .role(TransformationItemRole.RELEASED)
                    .build());
            transformation.addItem(TransformationItem.builder()
                    .item(containerItem)
                    .role(TransformationItemRole.INPUT)
                    .build());
            return null;
        }).when(handler).generate(any(), any(), any());
        when(repository.save(any(Transformation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateTransformationRequestDTO(
                containerItem.getId(), null, null, null, null, null, null, null));

        // Un componente liberado vuelve al inventario, no es un resultado por cobrar.
        verify(workResultService, never()).createPendingFromItem(componentA);
        verify(workResultService, never()).createPendingFromItem(componentB);
        verify(workResultService, never()).createPendingFromItem(containerItem);
        verify(workResultService, never()).createPendingFromItem(any());
    }

}
