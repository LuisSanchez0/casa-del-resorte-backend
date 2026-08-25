package com.cns.spring_house.vehiclemovement.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.cns.spring_house.s3.service.S3Service;
import com.cns.spring_house.shared.exception.EntityNotFoundException;
import com.cns.spring_house.shared.exception.ValidationException;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionResponseDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.UpdateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovementPhoto;
import com.cns.spring_house.vehiclemovement.mapper.VehicleMovementMapper;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementRepository;
import com.cns.spring_house.vehiclemovement.repository.VehicleMovementSpecs;
import com.cns.spring_house.workshop.entity.Workshop;
import com.cns.spring_house.workshop.repository.WorkshopRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional
@Service
public class VehicleMovementServiceImpl implements VehicleMovementService {

    /** Tope de fotografias por movimiento, no por peticion. */
    private static final int MAX_PHOTOS = 10;

    /**
     * Indice parcial creado en V18 y reforzado en V19. Se nombra aqui para poder
     * distinguir SU violacion de cualquier otra y no convertir toda falla de
     * integridad en un error de vehiculo duplicado.
     */
    private static final String OPEN_PLATE_INDEX =
            "uq_vehicle_movement_open_plate_per_workshop";

    /**
     * Roles a los que la respuesta les incluye el telefono de la bitacora.
     *
     * `GET /v1/vehicle-movements` no es un endpoint exclusivo de Policia: la
     * cadena de filtros solo exige estar autenticado y Vendedor ya lo consulta
     * para sugerir placas al facturar. El telefono, en cambio, solo tiene
     * destinatario en el historial policial y en el administrativo.
     *
     * Restringir el endpoint entero con @PreAuthorize romperia ese uso legitimo
     * de Vendedor, asi que lo que se acota es el CAMPO, no la peticion.
     */
    private static final String[] PHONE_VISIBLE_ROLES = { "POLICE", "ADMIN" };

    private final VehicleMovementRepository repository;
    private final VehicleMovementMapper mapper;
    private final WorkshopRepository workshopRepository;
    private final S3Service s3Service;
    private final CurrentUserService currentUserService;

    /**
     * El taller del token es el unico scope valido: `requestDTO.workshop()` se
     * ignora deliberadamente para que un query param no pueda ampliar el alcance.
     */
    @Override
    public List<VehicleMovementResponseDTO> getAll(SearchVehicleMovementRequestDTO requestDTO) {
        Specification<VehicleMovement> specs = Specification.allOf(
                VehicleMovementSpecs.plateContains(requestDTO.plate()),
                VehicleMovementSpecs.hasWorkshop(SecurityUtils.getWorkshopId()),
                VehicleMovementSpecs.hasEntry(requestDTO.entry()),
                VehicleMovementSpecs.hasExit(requestDTO.exit()));
        // El rol se resuelve UNA vez para toda la lista y no por elemento: la
        // visibilidad depende de quien pregunta, no de cada movimiento.
        boolean withPhone = canSeePhone();
        return repository.findAll(specs)
                .stream()
                .map(movement -> toResponse(movement, withPhone))
                .toList();
    }

    @Override
    public VehicleMovementResponseDTO get(UUID id) {
        VehicleMovement movement = findOwnedMovement(id);
        return toResponse(movement);
    }

    @Override
    public VehicleMovementResponseDTO create(CreateVehicleMovementRequestDTO requestDTO) {
        // El mismo UUID valida la placa y se asigna al registro: asi no puede
        // validarse contra un taller y persistirse en otro.
        UUID workshopId = SecurityUtils.getWorkshopId();
        // La placa ya llega normalizada por el record: se valida y se persiste
        // exactamente el mismo texto.
        boolean exists = repository
                .existsByPlateAndWorkshopIdAndExitIsNull(requestDTO.plate(), workshopId);
        if (exists) {
            throw new ValidationException(
                    "No se ha registrado una salida para este vehiculo");
        }
        List<MultipartFile> photos = nonEmptyPhotos(requestDTO.photos());
        validatePhotoLimit(photos.size());
        Workshop workshop = workshopRepository
                .findById(workshopId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el taller especificado"));
        VehicleMovement movement = mapper.toVehicleMovement(requestDTO);
        movement.setWorkshop(workshop);
        // Quien registra la entrada se resuelve desde el token (misma convencion
        // que daily_log, transformation, sale y job_log), nunca desde el
        // formulario: es lo unico que sostiene "Mis estadisticas".
        movement.setEmployee(currentUserService.getCurrentEmployee());
        // El telefono lo trae el mapper desde el DTO y se persiste TAL CUAL fue
        // validado. Se guarda una copia propia de esta bitacora: reutilizar el de
        // una visita anterior significa escribir el mismo texto otra vez en una
        // fila nueva, nunca apuntar a la fila vieja. Asi ninguna entrada futura
        // puede cambiar lo que muestra el historial de esta.
        uploadPhotos(movement, photos);
        return toResponse(saveDetectingOpenPlate(movement));
    }

    /**
     * La pertenencia al taller se resuelve en la PRIMERA linea, antes de tocar
     * notas, fotos o S3: un PATCH cruzado no debe llegar a subir ni borrar nada.
     */
    @Override
    public VehicleMovementResponseDTO update(UUID id, UpdateVehicleMovementRequestDTO requestDTO) {
        VehicleMovement movement = findOwnedMovement(id);
        // Todo lo que puede rechazar la peticion se resuelve antes de subir nada:
        // pertenencia, fotos a eliminar y total resultante.
        List<VehicleMovementPhoto> removedPhotos =
                resolvePhotosToRemove(movement, requestDTO.removedPhotos());
        List<MultipartFile> newPhotos = nonEmptyPhotos(requestDTO.photos());
        validatePhotoLimit(
                movement.getPhotos().size() - removedPhotos.size() + newPhotos.size());
        mapper.updateVehicleMovementFromDTO(requestDTO, movement);
        List<String> removedKeys = removedPhotos.stream()
                .map(VehicleMovementPhoto::getPhotoKey)
                .toList();
        removedPhotos.forEach(movement::removePhoto);
        uploadPhotos(movement, newPhotos);
        movement = repository.save(movement);
        removedKeys.forEach(s3Service::deleteObject);
        return toResponse(movement);
    }

    @Override
    public VehicleMovementResponseDTO registerExit(UUID id) {
        VehicleMovement movement = findOwnedMovement(id);
        if (movement.getExit() != null) {
            throw new ValidationException(
                    "Ya se marco una salida para este registro");
        }
        // Solo se escribe `exit`. El telefono se capturo al ABRIR la bitacora y
        // registrar la salida no vuelve a pedirlo ni lo modifica: cerrar una
        // visita no es un evento de contacto.
        movement.setExit(LocalDateTime.now());
        movement = repository.save(movement);
        return toResponse(movement);
    }

    /**
     * Ultimo telefono conocido de la placa EN EL TALLER DEL TOKEN, o null.
     *
     * Solo lee: no crea nada, no reserva la placa y no comprueba si tiene una
     * visita abierta. Que exista una sugerencia no significa que la entrada vaya
     * a admitirse; la regla de "un solo movimiento abierto por placa y taller"
     * se sigue evaluando entera en {@code create}, con su indice unico detras.
     *
     * Una placa desconocida, o conocida pero sin ningun telefono registrado,
     * devuelve `phone = null`. Es una respuesta normal y no un error: el alta
     * simplemente pide el numero desde cero.
     */
    @Transactional(readOnly = true)
    @Override
    public PhoneSuggestionResponseDTO getPhoneSuggestion(PhoneSuggestionRequestDTO requestDTO) {
        // La placa ya llega canonizada por el record, con la misma normalizacion
        // que el alta: se consulta exactamente la forma con la que se guardo.
        String phone = repository
                .findLatestPhoneByPlate(SecurityUtils.getWorkshopId(), requestDTO.plate())
                .orElse(null);
        // No se registra el valor en ningun log: es un dato de contacto y la
        // sugerencia no necesita rastro para funcionar.
        return new PhoneSuggestionResponseDTO(phone);
    }

    /**
     * Unica salida de este servicio hacia el DTO. Todo lo que responde el modulo
     * pasa por aqui, de modo que anadir un endpoint nuevo no puede olvidarse de
     * aplicar la regla de visibilidad del telefono.
     */
    private VehicleMovementResponseDTO toResponse(VehicleMovement movement) {
        return toResponse(movement, canSeePhone());
    }

    private VehicleMovementResponseDTO toResponse(VehicleMovement movement, boolean withPhone) {
        return withPhone
                ? mapper.toResponse(movement)
                : mapper.toResponseWithoutPhone(movement);
    }

    /**
     * Si al solicitante le corresponde ver el telefono de la bitacora.
     *
     * Es visibilidad de CAMPO, no autorizacion de endpoint: quien no lo puede ver
     * recibe igualmente el movimiento completo, solo que con el telefono en null,
     * indistinguible de una bitacora historica que nunca lo tuvo.
     */
    private boolean canSeePhone() {
        return SecurityUtils.hasAnyRole(PHONE_VISIBLE_ROLES);
    }

    /**
     * Unico punto de resolucion de un movimiento concreto. Un registro de otro
     * taller responde exactamente igual que uno inexistente: confirmar que "existe
     * pero es ajeno" ya filtraria informacion de otro taller.
     */
    private VehicleMovement findOwnedMovement(UUID id) {
        return repository
                .findByIdAndWorkshopId(id, SecurityUtils.getWorkshopId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No existe el registro especificado"));
    }

    /**
     * Un formulario sin fotos igual envia la parte, asi que los multipart vacios
     * se descartan antes de contarlos: si no, gastarian cupo del tope.
     */
    private List<MultipartFile> nonEmptyPhotos(List<MultipartFile> photos) {
        if (photos == null) {
            return List.of();
        }
        return photos.stream()
                .filter(photo -> !photo.isEmpty())
                .toList();
    }

    /**
     * El tope es del movimiento, no de la peticion: `@Size` solo limita las fotos
     * que vienen en el request y sin esto varios PATCH sucesivos lo superarian.
     */
    private void validatePhotoLimit(int total) {
        if (total > MAX_PHOTOS) {
            throw new ValidationException(
                    "No se pueden adjuntar mas de " + MAX_PHOTOS + " fotografias");
        }
    }

    /** Sube cada archivo a S3 y guarda unicamente la key devuelta. */
    private void uploadPhotos(VehicleMovement movement, List<MultipartFile> photos) {
        photos.forEach(photo -> movement.addPhoto(VehicleMovementPhoto.builder()
                .photoKey(s3Service.uploadImage(photo))
                .build()));
    }

    /**
     * Solo identifica y valida: no desprende nada. El total final debe conocerse
     * antes de decidir si la peticion es valida, y por tanto antes de mutar.
     */
    private List<VehicleMovementPhoto> resolvePhotosToRemove(
            VehicleMovement movement, List<UUID> photoIds) {
        if (photoIds == null || photoIds.isEmpty()) {
            return List.of();
        }
        List<VehicleMovementPhoto> photos = movement.getPhotos()
                .stream()
                .filter(photo -> photoIds.contains(photo.getId()))
                .toList();
        if (photos.size() != photoIds.stream().distinct().count()) {
            throw new ValidationException(
                    "Una o mas fotografias no pertenecen al registro especificado");
        }
        return photos;
    }

    /**
     * `saveAndFlush` y no `save`: el id lo genera @UuidGenerator, asi que Hibernate
     * no necesita el INSERT para obtenerlo y lo aplazaria hasta el commit, fuera de
     * este metodo. Forzar el flush deja la violacion dentro del try.
     * <p>
     * Solo se traduce la del indice de placa abierta —comparando su nombre—; una
     * violacion distinta se relanza y sigue siendo un 500 con detalle solo en log.
     */
    private VehicleMovement saveDetectingOpenPlate(VehicleMovement movement) {
        try {
            return repository.saveAndFlush(movement);
        } catch (DataIntegrityViolationException exception) {
            if (isOpenPlateViolation(exception)) {
                throw new ValidationException(
                        "No se ha registrado una salida para este vehiculo");
            }
            throw exception;
        }
    }

    private boolean isOpenPlateViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return OPEN_PLATE_INDEX.equalsIgnoreCase(violation.getConstraintName());
            }
            current = current.getCause();
        }
        return false;
    }

}
