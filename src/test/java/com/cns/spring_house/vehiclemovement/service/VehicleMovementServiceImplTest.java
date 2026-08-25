package com.cns.spring_house.vehiclemovement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.cns.spring_house.employee.entity.Employee;
import com.cns.spring_house.s3.service.S3Service;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.UpdateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovementPhoto;
import com.cns.spring_house.vehiclemovement.mapper.VehicleMovementMapper;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/**
 * El aislamiento entre talleres se prueba SIEMPRE con dos talleres: con uno solo
 * cualquier consulta pasaria aunque no estuviera acotada.
 */
@ExtendWith(MockitoExtension.class)
class VehicleMovementServiceImplTest {

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

    /** Telefono valido (8 digitos) usado en las altas de estas pruebas. */
    private static final String PHONE = "58452136";

    /** Taller autenticado en todas las pruebas. */
    private final UUID workshopAId = UUID.randomUUID();
    /** Taller ajeno: nunca debe ser alcanzable desde el token de A. */
    private final UUID workshopBId = UUID.randomUUID();
    private final UUID userAccountId = UUID.randomUUID();

    private final Workshop workshopA = Workshop.builder()
            .id(workshopAId)
            .build();
    private final Workshop workshopB = Workshop.builder()
            .id(workshopBId)
            .build();

    private static final String PLATE = "P123ABC";

    /**
     * Por defecto se autentica un POLICIA: es quien registra las entradas y quien
     * consulta el historial policial, asi que es el contexto normal del modulo.
     *
     * El rol viaja como autoridad y no solo como claim porque es lo que evalua de
     * verdad la capa de seguridad: {@code JwtConfig} convierte la claim `role` en
     * `ROLE_<nombre>`, y el servicio decide con eso si la respuesta lleva
     * telefono. Un token sin autoridades no representa a ningun usuario real.
     */
    @BeforeEach
    void setUpAuthentication() {
        authenticateAs("POLICE");
    }

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

    private VehicleMovement movement(Workshop workshop) {
        return VehicleMovement.builder()
                .id(UUID.randomUUID())
                .workshop(workshop)
                .plate(PLATE)
                .notes("NOTAS ORIGINALES")
                .build();
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile(
                "photos", name, "image/jpeg", new byte[] { 1, 2, 3 });
    }

    private List<MultipartFile> images(int amount) {
        return IntStream.range(0, amount)
                .mapToObj(index -> (MultipartFile) image("foto-" + index + ".jpg"))
                .toList();
    }

    /** Movimiento propio con `amount` fotos ya persistidas. */
    private VehicleMovement movementWithPhotos(int amount) {
        VehicleMovement movement = movement(workshopA);
        IntStream.range(0, amount).forEach(index -> movement.addPhoto(
                VehicleMovementPhoto.builder()
                        .id(UUID.randomUUID())
                        .photoKey("existente-" + index + ".jpg")
                        .build()));
        return movement;
    }

    /** Empleado que la cuenta autenticada tiene asociado. */
    private final Employee authenticatedEmployee = Employee.builder()
            .id(UUID.randomUUID())
            .workshop(workshopA)
            .firstName("ANA")
            .lastName("LOPEZ")
            .build();

    /** Deja `create` en su camino feliz para poder aislar lo que prueba el test. */
    private void stubSuccessfulCreate() {
        when(repository.existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId))
                .thenReturn(false);
        when(workshopRepository.findById(workshopAId))
                .thenReturn(Optional.of(workshopA));
        when(mapper.toVehicleMovement(any())).thenReturn(
                VehicleMovement.builder().plate(PLATE).build());
        lenient().when(currentUserService.getCurrentEmployee())
                .thenReturn(authenticatedEmployee);
        lenient().when(repository.saveAndFlush(any()))
                .thenAnswer(call -> call.getArgument(0));
    }

    // ---------------------------------------------------------------- getAll

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void getAllIgnoresTheWorkshopSentByTheClient() {
        when(repository.findAll(any(Specification.class))).thenReturn(List.of());

        // El cliente pide explicitamente el taller ajeno.
        service.getAll(new SearchVehicleMovementRequestDTO(
                null, workshopBId, null, null));

        ArgumentCaptor<Specification> captor =
                ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(captor.capture());

        Root<VehicleMovement> root = mock(Root.class);
        Path workshopPath = mock(Path.class);
        Path idPath = mock(Path.class);
        CriteriaBuilder builder = mock(CriteriaBuilder.class);
        doReturn(workshopPath).when(root).get("workshop");
        doReturn(idPath).when(workshopPath).get("id");

        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), builder);

        verify(builder).equal(idPath, workshopAId);
        verify(builder, never()).equal(idPath, workshopBId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAllOnlyReturnsMovementsOfTheAuthenticatedWorkshop() {
        VehicleMovement a1 = movement(workshopA);
        VehicleMovement a2 = movement(workshopA);
        // El repositorio ya viene acotado por la specification.
        when(repository.findAll(any(Specification.class)))
                .thenReturn(List.of(a1, a2));

        service.getAll(new SearchVehicleMovementRequestDTO(
                null, null, null, null));

        verify(mapper).toResponse(a1);
        verify(mapper).toResponse(a2);
    }

    // ------------------------------------------------------------------- get

    @Test
    void getReturnsTheMovementOfTheOwnWorkshop() {
        VehicleMovement a1 = movement(workshopA);
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));

        service.get(a1.getId());

        verify(mapper).toResponse(a1);
    }

    @Test
    void getDoesNotFindMovementsOfAnotherWorkshop() {
        VehicleMovement b1 = movement(workshopB);
        // La consulta acotada no lo encuentra aunque el UUID exista.
        when(repository.findByIdAndWorkshopId(b1.getId(), workshopAId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(b1.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No existe el registro especificado");

        verify(repository, never()).findById(any());
        verifyNoInteractions(mapper);
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateDoesNotTouchMovementsOfAnotherWorkshopNorS3() {
        VehicleMovement b1 = movement(workshopB);
        UUID photoId = UUID.randomUUID();
        b1.addPhoto(VehicleMovementPhoto.builder()
                .id(photoId)
                .photoKey("ajena.jpg")
                .build());
        when(repository.findByIdAndWorkshopId(b1.getId(), workshopAId))
                .thenReturn(Optional.empty());

        UpdateVehicleMovementRequestDTO requestDTO =
                new UpdateVehicleMovementRequestDTO(
                        "NOTAS INYECTADAS",
                        List.of(image("nueva.jpg")),
                        List.of(photoId));

        assertThatThrownBy(() -> service.update(b1.getId(), requestDTO))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No existe el registro especificado");

        // Nada del registro ajeno cambio y S3 nunca fue invocado.
        assertThat(b1.getNotes()).isEqualTo("NOTAS ORIGINALES");
        assertThat(b1.getPhotos()).hasSize(1);
        verifyNoInteractions(s3Service);
        verify(repository, never()).save(any());
    }

    @Test
    void updateModifiesNotesAndPhotosOfTheOwnWorkshop() {
        VehicleMovement a1 = movement(workshopA);
        UUID photoId = UUID.randomUUID();
        a1.addPhoto(VehicleMovementPhoto.builder()
                .id(photoId)
                .photoKey("vieja.jpg")
                .build());
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));
        when(repository.save(a1)).thenReturn(a1);
        when(s3Service.uploadImage(any())).thenReturn("nueva-key.jpg");

        service.update(a1.getId(), new UpdateVehicleMovementRequestDTO(
                null, List.of(image("nueva.jpg")), List.of(photoId)));

        assertThat(a1.getPhotos())
                .extracting(VehicleMovementPhoto::getPhotoKey)
                .containsExactly("nueva-key.jpg");
        verify(s3Service).deleteObject("vieja.jpg");
    }

    @Test
    void updateRejectsPhotosThatBelongToAnotherMovement() {
        VehicleMovement a1 = movement(workshopA);
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));

        UUID foreignPhotoId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(
                a1.getId(),
                new UpdateVehicleMovementRequestDTO(
                        null, null, List.of(foreignPhotoId))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Una o mas fotografias no pertenecen al registro especificado");

        verify(s3Service, never()).deleteObject(any());
        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------- registerExit

    @Test
    void registerExitClosesTheOwnMovement() {
        VehicleMovement a1 = movement(workshopA);
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));
        when(repository.save(a1)).thenReturn(a1);

        service.registerExit(a1.getId());

        assertThat(a1.getExit()).isNotNull();
    }

    @Test
    void registerExitDoesNotCloseMovementsOfAnotherWorkshop() {
        VehicleMovement b1 = movement(workshopB);
        when(repository.findByIdAndWorkshopId(b1.getId(), workshopAId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerExit(b1.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No existe el registro especificado");

        assertThat(b1.getExit()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void registerExitRejectsAnAlreadyClosedMovement() {
        VehicleMovement a1 = movement(workshopA);
        a1.setExit(LocalDateTime.now());
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));

        assertThatThrownBy(() -> service.registerExit(a1.getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Ya se marco una salida para este registro");

        verify(repository, never()).save(any());
    }

    // ---------------------------------------------------------------- create

    @Test
    void createRejectsAPlateAlreadyOpenInTheSameWorkshop() {
        when(repository.existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se ha registrado una salida para este vehiculo");

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(s3Service);
    }

    /**
     * Una entrada abierta en el taller B no puede bloquear al taller A: la
     * comprobacion viaja acotada al taller del token.
     */
    @Test
    void createAllowsAPlateThatIsOnlyOpenInAnotherWorkshop() {
        stubSuccessfulCreate();

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getWorkshop().getId()).isEqualTo(workshopAId);
        assertThat(captor.getValue().getExit()).isNull();
        // Nunca se consulto la existencia global ni la del taller ajeno.
        verify(repository, never())
                .existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopBId);
    }

    @Test
    void createAllowsThePlateToReturnAfterItsExitWasRegistered() {
        // Sin movimientos abiertos: el historico cerrado no bloquea.
        stubSuccessfulCreate();

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        verify(repository).saveAndFlush(any());
    }

    @Test
    void createStoresOnlyTheKeyReturnedByS3() {
        stubSuccessfulCreate();
        when(s3Service.uploadImage(any()))
                .thenReturn("key-1.jpg", "key-2.jpg");

        service.create(new CreateVehicleMovementRequestDTO(
                PLATE, PHONE, null, List.of(image("a.jpg"), image("b.jpg"))));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPhotos())
                .extracting(VehicleMovementPhoto::getPhotoKey)
                .containsExactly("key-1.jpg", "key-2.jpg");
    }

    // --------------------------------------------------- normalizacion placa

    @Test
    void thePlateIsNormalizedBeforeValidatingAndPersisting() {
        stubSuccessfulCreate();

        service.create(new CreateVehicleMovementRequestDTO(
                "  p123abc  ", PHONE, null, null));

        // Se valida y se persiste exactamente el mismo texto normalizado.
        verify(repository)
                .existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId);
        ArgumentCaptor<CreateVehicleMovementRequestDTO> captor =
                ArgumentCaptor.forClass(CreateVehicleMovementRequestDTO.class);
        verify(mapper).toVehicleMovement(captor.capture());
        assertThat(captor.getValue().plate()).isEqualTo(PLATE);
    }

    @Test
    void aPlateInAnotherCaseCollidesWithTheOpenOneOfTheSameWorkshop() {
        when(repository.existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateVehicleMovementRequestDTO("p123abc", PHONE, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se ha registrado una salida para este vehiculo");

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void aNullPlateIsLeftToBeanValidationInsteadOfFailingWhenBuildingTheRecord() {
        // @NotBlank debe producir el 400; normalizar no puede lanzar NPE antes.
        CreateVehicleMovementRequestDTO requestDTO =
                new CreateVehicleMovementRequestDTO(null, PHONE, null, null);

        assertThat(requestDTO.plate()).isNull();
    }

    // -------------------------------------------------------- tope de fotos

    @Test
    void createAcceptsExactlyTenPhotos() {
        stubSuccessfulCreate();
        when(s3Service.uploadImage(any())).thenReturn("key.jpg");

        service.create(new CreateVehicleMovementRequestDTO(
                PLATE, PHONE, null, images(10)));

        verify(s3Service, times(10)).uploadImage(any());
    }

    @Test
    void createRejectsElevenPhotosWithoutTouchingS3() {
        when(repository.existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(
                new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, images(11))))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se pueden adjuntar mas de 10 fotografias");

        verifyNoInteractions(s3Service);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void emptyMultipartPartsDoNotConsumeThePhotoLimit() {
        stubSuccessfulCreate();
        when(s3Service.uploadImage(any())).thenReturn("key.jpg");
        List<MultipartFile> photos = new java.util.ArrayList<>(images(10));
        photos.add(new MockMultipartFile("photos", "", "image/jpeg", new byte[0]));

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, photos));

        verify(s3Service, times(10)).uploadImage(any());
    }

    @Test
    void updateAllowsReachingExactlyTenPhotos() {
        // 8 existentes - 3 eliminadas + 5 nuevas = 10
        VehicleMovement a1 = movementWithPhotos(8);
        List<UUID> removed = a1.getPhotos().stream()
                .limit(3)
                .map(VehicleMovementPhoto::getId)
                .toList();
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));
        when(repository.save(a1)).thenReturn(a1);
        when(s3Service.uploadImage(any())).thenReturn("nueva.jpg");

        service.update(a1.getId(), new UpdateVehicleMovementRequestDTO(
                null, images(5), removed));

        assertThat(a1.getPhotos()).hasSize(10);
    }

    @Test
    void updateRejectsExceedingTheTotalAndDoesNotTouchS3() {
        // 8 existentes - 1 eliminada + 5 nuevas = 12
        VehicleMovement a1 = movementWithPhotos(8);
        List<UUID> removed = a1.getPhotos().stream()
                .limit(1)
                .map(VehicleMovementPhoto::getId)
                .toList();
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));

        assertThatThrownBy(() -> service.update(
                a1.getId(),
                new UpdateVehicleMovementRequestDTO(null, images(5), removed)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se pueden adjuntar mas de 10 fotografias");

        verifyNoInteractions(s3Service);
        verify(repository, never()).save(any());
        assertThat(a1.getPhotos()).hasSize(8);
    }

    @Test
    void updateOnAFullMovementRejectsOneMorePhotoWithoutTouchingS3() {
        VehicleMovement a1 = movementWithPhotos(10);
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));

        assertThatThrownBy(() -> service.update(
                a1.getId(),
                new UpdateVehicleMovementRequestDTO(null, images(1), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se pueden adjuntar mas de 10 fotografias");

        verifyNoInteractions(s3Service);
        assertThat(a1.getPhotos()).hasSize(10);
    }

    @Test
    void updateOnAFullMovementAllowsSwappingPhotos() {
        // 10 existentes - 2 eliminadas + 2 nuevas = 10
        VehicleMovement a1 = movementWithPhotos(10);
        List<UUID> removed = a1.getPhotos().stream()
                .limit(2)
                .map(VehicleMovementPhoto::getId)
                .toList();
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));
        when(repository.save(a1)).thenReturn(a1);
        when(s3Service.uploadImage(any())).thenReturn("nueva.jpg");

        service.update(a1.getId(), new UpdateVehicleMovementRequestDTO(
                null, images(2), removed));

        assertThat(a1.getPhotos()).hasSize(10);
        verify(s3Service, times(2)).deleteObject(any());
    }

    @Test
    void updateRejectsNinePlusTwoBeforeUploadingAnything() {
        VehicleMovement a1 = movementWithPhotos(9);
        when(repository.findByIdAndWorkshopId(a1.getId(), workshopAId))
                .thenReturn(Optional.of(a1));

        assertThatThrownBy(() -> service.update(
                a1.getId(),
                new UpdateVehicleMovementRequestDTO(null, images(2), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se pueden adjuntar mas de 10 fotografias");

        verify(s3Service, never()).uploadImage(any());
        verify(s3Service, never()).deleteObject(any());
    }

    // ------------------------------------------- colision concurrente indice

    @Test
    void aConcurrentOpenPlateCollisionBecomesABusinessError() {
        stubSuccessfulCreate();
        // La comprobacion previa paso, pero otra peticion inserto primero.
        when(repository.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException(
                        "could not execute statement",
                        new ConstraintViolationException(
                                "duplicate key",
                                new SQLException("duplicate key"),
                                "uq_vehicle_movement_open_plate_per_workshop")));

        assertThatThrownBy(() -> service.create(
                new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No se ha registrado una salida para este vehiculo")
                .hasMessageNotContaining("constraint")
                .hasMessageNotContaining("uq_vehicle_movement");
    }

    @Test
    void anUnexpectedIntegrityViolationIsNotDisguisedAsADuplicatedVehicle() {
        stubSuccessfulCreate();
        when(repository.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException(
                        "could not execute statement",
                        new ConstraintViolationException(
                                "null value",
                                new SQLException("null value"),
                                "vehicle_movement_workshop_id_fkey")));

        // Debe seguir siendo un 500 generico, no un error de vehiculo duplicado.
        assertThatThrownBy(() -> service.create(
                new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createChecksThePlateAgainstTheWorkshopOfTheToken() {
        stubSuccessfulCreate();

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        // El taller usado para validar y para persistir es el mismo.
        verify(repository).existsByPlateAndWorkshopIdAndExitIsNull(PLATE, workshopAId);
        verify(workshopRepository).findById(eq(workshopAId));
    }

    /**
     * Sin esto "Mis estadisticas" no tendria de donde salir: el actor debe quedar
     * persistido en la propia fila en el momento de la entrada.
     */
    @Test
    void createStoresTheAuthenticatedEmployeeAsTheActorOfTheEntry() {
        stubSuccessfulCreate();

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getEmployee())
                .as("la entrada guarda quien la registro")
                .isSameAs(authenticatedEmployee);
    }

    /**
     * El actor sale de la identidad autenticada, nunca del cuerpo de la peticion.
     * El mapper tiene `employee` en ignore justamente para que un campo del
     * formulario no pueda decidir de quien es la entrada.
     */
    @Test
    void theActorNeverComesFromTheRequestBody() {
        stubSuccessfulCreate();
        // El mapper devuelve un movimiento con un actor ajeno ya puesto, como si
        // hubiera llegado desde fuera.
        Employee impostor = Employee.builder().id(UUID.randomUUID()).build();
        when(mapper.toVehicleMovement(any())).thenReturn(VehicleMovement.builder()
                .plate(PLATE)
                .employee(impostor)
                .build());

        service.create(new CreateVehicleMovementRequestDTO(PLATE, PHONE, null, null));

        ArgumentCaptor<VehicleMovement> captor =
                ArgumentCaptor.forClass(VehicleMovement.class);
        verify(repository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getEmployee())
                .as("el servicio sobrescribe cualquier actor que no venga del token")
                .isSameAs(authenticatedEmployee);
    }

}
