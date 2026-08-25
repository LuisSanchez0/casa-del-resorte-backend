package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.s3.service.S3Service;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionResponseDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.UpdateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.mapper.VehicleMovementMapper;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

/**
 * El telefono de la bitacora en la capa de servicio: quien puede verlo, de donde
 * sale la sugerencia y que el alta persista exactamente lo que se valido.
 *
 * La visibilidad se prueba con roles DISTINTOS y no solo con uno: con un unico
 * rol autorizado cualquier implementacion pasaria, incluida la que no comprueba
 * nada. `GET /v1/vehicle-movements` no es exclusivo de Policia —Vendedor lo
 * consulta para sugerir placas al facturar— y ese es justo el caso que hay que
 * demostrar que no recibe telefonos.
 */
@ExtendWith(MockitoExtension.class)
class VehicleMovementPhoneServiceTest {

    private static final String PLATE = "P123ABC";
    private static final String PHONE = "58452136";

    @Mock
    private VehicleMovementRepository repository;

    @Mock
    private VehicleMovementMapper mapper;

    @Mock
    private WorkshopRepository workshopRepository;

    @Mock
    private S3Service s3Service;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private VehicleMovementServiceImpl service;

    private final UUID workshopAId = UUID.randomUUID();
    private final UUID workshopBId = UUID.randomUUID();
    private final UUID userAccountId = UUID.randomUUID();

    private final Workshop workshopA = Workshop.builder().id(workshopAId).build();

    private final Employee authenticatedEmployee = Employee.builder()
            .id(UUID.randomUUID())
            .workshop(workshopA)
            .build();

    private void authenticateAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userAccountId.toString())
                .claim("workshop", workshopAId.toString())
                .claim("role", role)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private VehicleMovement movement() {
        return VehicleMovement.builder()
                .id(UUID.randomUUID())
                .workshop(workshopA)
                .plate(PLATE)
                .phone(PHONE)
                .build();
    }

    private void stubSuccessfulCreate() {
        when(repository.existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId))
                .thenReturn(false);
        when(workshopRepository.findById(workshopAId))
                .thenReturn(Optional.of(workshopA));
        lenient().when(currentUserService.getCurrentEmployee())
                .thenReturn(authenticatedEmployee);
        lenient().when(repository.saveAndFlush(any()))
                .thenAnswer(call -> call.getArgument(0));
    }

    // ── Visibilidad por rol ──────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = { "POLICE", "ADMIN" })
    @DisplayName("el historial de POLICE y de ADMIN incluye el telefono")
    @SuppressWarnings("unchecked")
    void policeAndAdminSeeThePhone(String role) {
        authenticateAs(role);
        VehicleMovement first = movement();
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(first));

        service.getAll(new SearchVehicleMovementRequestDTO(null, null, null, null));

        verify(mapper).toResponse(first);
        verify(mapper, never()).toResponseWithoutPhone(any());
    }

    /**
     * Los demas roles siguen leyendo el endpoint —Vendedor lo necesita para
     * sugerir placas— pero su respuesta sale SIN telefono. Lo que se acota es el
     * campo, no la peticion: restringir el endpoint entero romperia ese uso.
     */
    @ParameterizedTest
    @ValueSource(strings = { "SELLER", "SECRETARY", "OPERATOR", "CONTROL" })
    @DisplayName("ningun otro rol recibe el telefono en el historial")
    @SuppressWarnings("unchecked")
    void otherRolesNeverSeeThePhone(String role) {
        authenticateAs(role);
        VehicleMovement first = movement();
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(first));

        service.getAll(new SearchVehicleMovementRequestDTO(null, null, null, null));

        verify(mapper).toResponseWithoutPhone(first);
        verify(mapper, never()).toResponse(any());
    }

    @Test
    @DisplayName("el detalle de un movimiento respeta la misma regla de visibilidad")
    void thedetailAppliesTheSameVisibilityRule() {
        authenticateAs("SELLER");
        VehicleMovement first = movement();
        when(repository.findByIdAndWorkshopId(first.getId(), workshopAId))
                .thenReturn(Optional.of(first));

        service.get(first.getId());

        verify(mapper).toResponseWithoutPhone(first);
        verify(mapper, never()).toResponse(any());
    }

    @Test
    @DisplayName("un token sin rol no obtiene telefono")
    @SuppressWarnings("unchecked")
    void atokenWithoutRoleGetsNoPhone() {
        // Falla en cerrado: sin rol demostrable no se entrega el dato.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(userAccountId.toString())
                .claim("workshop", workshopAId.toString())
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));

        VehicleMovement first = movement();
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(first));

        service.getAll(new SearchVehicleMovementRequestDTO(null, null, null, null));

        verify(mapper).toResponseWithoutPhone(first);
        verify(mapper, never()).toResponse(any());
    }

    // ── Alta ─────────────────────────────────────────────────────

    @Test
    @DisplayName("el alta persiste el telefono validado, sin retocarlo")
    void createPersistsTheValidatedPhone() {
        authenticateAs("POLICE");
        stubSuccessfulCreate();
        when(mapper.toVehicleMovement(any())).thenAnswer(call -> {
            CreateVehicleMovementRequestDTO request = call.getArgument(0);
            return VehicleMovement.builder()
                    .plate(request.plate())
                    .phone(request.phone())
                    .build();
        });

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo(PHONE);
    }

    /**
     * Reutilizar el numero anterior es escribir el MISMO texto en una fila nueva.
     * El servicio no distingue "reutilizado" de "escrito a mano": lo que llega es
     * un telefono y se guarda como valor propio de esta bitacora.
     */
    @Test
    @DisplayName("un telefono reutilizado se guarda como valor propio de la fila nueva")
    void areusedPhoneIsStoredAsItsOwnValue() {
        authenticateAs("POLICE");
        stubSuccessfulCreate();
        when(mapper.toVehicleMovement(any())).thenAnswer(call -> {
            CreateVehicleMovementRequestDTO request = call.getArgument(0);
            return VehicleMovement.builder()
                    .plate(request.plate())
                    .phone(request.phone())
                    .build();
        });
        // El repositorio conoce ese numero de una visita anterior.
        lenient().when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("sustituir el telefono a mano persiste el nuevo, no el sugerido")
    void amanualReplacementPersistsTheNewValue() {
        authenticateAs("POLICE");
        stubSuccessfulCreate();
        when(mapper.toVehicleMovement(any())).thenAnswer(call -> {
            CreateVehicleMovementRequestDTO request = call.getArgument(0);
            return VehicleMovement.builder()
                    .plate(request.plate())
                    .phone(request.phone())
                    .build();
        });
        lenient().when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        service.create(new CreateVehicleMovementRequestDTO(PLATE, "55554444", null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo("55554444");
    }

    /**
     * La sugerencia es una lectura y nada mas. Que exista no puede hacer que el
     * alta se salte la comprobacion de visita abierta.
     */
    @Test
    @DisplayName("tener sugerencia no evita la comprobacion de visita abierta")
    void asuggestionDoesNotSkipTheOpenMovementCheck() {
        authenticateAs("POLICE");
        lenient().when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));
        when(repository.existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId))
                .thenReturn(true);

        assertThat(catchThrowable(() -> service.create(
                new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null))))
                .hasMessageContaining("No se ha registrado una salida para este vehiculo");

        verify(repository, never()).saveAndFlush(any());
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    // ── Salida y edicion ─────────────────────────────────────────

    @Test
    @DisplayName("registrar la salida no vuelve a pedir ni a escribir el telefono")
    void registeringExitDoesNotTouchThePhone() {
        authenticateAs("POLICE");
        VehicleMovement open = movement();
        when(repository.findByIdAndWorkshopId(open.getId(), workshopAId))
                .thenReturn(Optional.of(open));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.registerExit(open.getId());

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getExit()).isNotNull();
        assertThat(captor.getValue().getPhone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("editar notas y fotografias no cambia el telefono")
    void updatingNotesDoesNotChangeThePhone() {
        authenticateAs("POLICE");
        VehicleMovement existing = movement();
        when(repository.findByIdAndWorkshopId(existing.getId(), workshopAId))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.update(
                existing.getId(),
                new UpdateVehicleMovementRequestDTO("OTRAS NOTAS", null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo(PHONE);
    }

    // ── Sugerencia ───────────────────────────────────────────────

    @Test
    @DisplayName("la sugerencia consulta SIEMPRE con el taller del token")
    void thesuggestionUsesTheWorkshopFromTheToken() {
        authenticateAs("POLICE");
        when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        PhoneSuggestionResponseDTO response =
                service.getPhoneSuggestion(new PhoneSuggestionRequestDTO(PLATE));

        assertThat(response.phone()).isEqualTo(PHONE);
        verify(repository).findLatestPhoneByPlate(workshopAId, PLATE);
        verify(repository, never()).findLatestPhoneByPlate(workshopBId, PLATE);
    }

    @Test
    @DisplayName("sin telefono previo la sugerencia es null, no un error")
    void nopreviousPhoneMeansNullNotAnError() {
        authenticateAs("POLICE");
        when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.empty());

        assertThat(service.getPhoneSuggestion(new PhoneSuggestionRequestDTO(PLATE)).phone())
                .isNull();
    }

    @Test
    @DisplayName("la sugerencia consulta la placa ya canonizada")
    void thesuggestionQueriesTheCanonicalPlate() {
        authenticateAs("POLICE");
        when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        service.getPhoneSuggestion(new PhoneSuggestionRequestDTO("  p123abc  "));

        verify(repository).findLatestPhoneByPlate(workshopAId, PLATE);
    }

    /** No crea nada: es estrictamente una lectura. */
    @Test
    @DisplayName("la sugerencia no escribe en la base")
    void thesuggestionDoesNotWrite() {
        authenticateAs("POLICE");
        when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        service.getPhoneSuggestion(new PhoneSuggestionRequestDTO(PLATE));

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("la sugerencia devuelve solo el telefono, sin el movimiento de origen")
    void thesuggestionOnlyReturnsThePhone() {
        authenticateAs("POLICE");
        when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        PhoneSuggestionResponseDTO response =
                service.getPhoneSuggestion(new PhoneSuggestionRequestDTO(PLATE));

        // Un solo componente en el record: ni id, ni fecha, ni historial.
        assertThat(PhoneSuggestionResponseDTO.class.getRecordComponents()).hasSize(1);
        assertThat(response.phone()).isEqualTo(PHONE);
    }

    /** El movimiento del que sale la sugerencia jamas se carga entero. */
    @Test
    @DisplayName("la sugerencia no descarga el historial de la placa")
    @SuppressWarnings("unchecked")
    void thesuggestionDoesNotLoadTheHistory() {
        authenticateAs("POLICE");
        when(repository.findLatestPhoneByPlate(workshopAId, PLATE))
                .thenReturn(Optional.of(PHONE));

        service.getPhoneSuggestion(new PhoneSuggestionRequestDTO(PLATE));

        verify(repository, never()).findAll(any(Specification.class));
        verify(repository, never()).findAll();
    }

    /** Fecha fija para que la prueba no dependa de cuando se ejecute. */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 3, 1, 9, 0);

    @Test
    @DisplayName("el alta no propaga el telefono a movimientos ya existentes")
    void createDoesNotPropagateThePhoneToExistingRows() {
        authenticateAs("POLICE");
        VehicleMovement older = VehicleMovement.builder()
                .id(UUID.randomUUID())
                .workshop(workshopA)
                .plate(PLATE)
                .entry(FIXED)
                .exit(FIXED.plusHours(1))
                .phone("11111111")
                .build();

        stubSuccessfulCreate();
        when(mapper.toVehicleMovement(any())).thenAnswer(call -> {
            CreateVehicleMovementRequestDTO request = call.getArgument(0);
            return VehicleMovement.builder()
                    .plate(request.plate())
                    .phone(request.phone())
                    .build();
        });

        service.create(new CreateVehicleMovementRequestDTO(PLATE, "22222222", null, null));

        // La fila anterior conserva el suyo: el alta no la toca ni por error.
        assertThat(older.getPhone()).isEqualTo("11111111");
        verify(repository, never()).save(older);
    }
}
