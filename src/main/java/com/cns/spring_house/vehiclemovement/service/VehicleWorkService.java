package com.cns.spring_house.vehiclemovement.service;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import com.cns.spring_house.vehiclemovement.repository.*;
import com.cns.spring_house.vehiclemovement.dto.*;
import com.cns.spring_house.vehiclemovement.entity.VehicleMechanicAssignmentEvent;
import com.cns.spring_house.vehiclemovement.entity.VehicleMovement;
import com.cns.spring_house.vehiclemovement.mapper.VehicleMovementMapper;
import com.cns.spring_house.useraccount.entity.UserAccount;
import com.cns.spring_house.shared.security.CurrentUserService;
import com.cns.spring_house.shared.security.SecurityUtils;
import com.cns.spring_house.shared.exception.*;

@Service
@RequiredArgsConstructor
@Transactional
public class VehicleWorkService {
    private final VehicleMovementRepository visits;
    private final VehicleMechanicRepository mechanics;
    private final VehicleMovementMapper mapper;
    private final VehicleMechanicAssignmentEventRepository assignmentEvents;
    private final CurrentUserService currentUser;
    private final VehicleWorkIntervalRepository intervals;
    private final VehicleWorkTimingService timings;

    /** Largo maximo del motivo de una reasignacion; el mismo limite usan la columna (V48) y Angular. */
    public static final int REASON_MAX = 300;

    @PreAuthorize("hasRole('POLICE')")
    public List<MechanicDTO> search(String search) {
        return mechanics.search(SecurityUtils.getWorkshopId(), search == null ? "" : search.trim(), PageRequest.of(0, 50));
    }
    public UserAccount requireEligible(UUID id, UUID workshop) {
        if (id == null) throw new ValidationException("Selecciona un mecánico del taller");
        return mechanics.eligible(id, workshop).orElseThrow(() -> new ValidationException(
            "El mecánico debe ser un operador activo de este taller. Selecciona otro mecánico."));
    }
    /**
     * Inicia el trabajo de UNA visita y fija, en la MISMA transaccion, al
     * mecanico responsable elegido en el dialogo. Todo o nada: mecanico, evento
     * de bitacora (V47/V48), primer inicio y primer tramo ACTIVO (V49) se
     * confirman juntos; cualquier rechazo o error revierte todo.
     * <ol>
     *   <li>visita del taller del token, bloqueada {@code FOR UPDATE} (ajena: 404);</li>
     *   <li>dentro del taller y nunca iniciada (si no: 400);</li>
     *   <li>{@code expectedMechanicId} = mecanico vigente (si no: 409, otra sesion lo cambio);</li>
     *   <li>elegido = operador activo del taller, bloqueado {@code FOR SHARE} (si no: 400);</li>
     *   <li>visita sin mecanico: evento INITIAL_ASSIGNMENT. Mismo mecanico que una
     *       asignacion previa: ningun evento. Otro mecanico: REASSIGNMENT con motivo
     *       obligatorio y nombres de este instante;</li>
     *   <li>{@code work_started_at} = instante del servidor y tramo START abierto.</li>
     * </ol>
     */
    @PreAuthorize("hasRole('POLICE')")
    public VehicleMovementResponseDTO start(UUID id, StartWorkRequestDTO request) {
        if (request != null) rejectUnexpected(request.unexpected());
        VehicleMovement visit = owned(id);
        if (visit.getExit() != null)
            throw new ValidationException("El vehículo ya salió del taller; esta visita ya no puede iniciar trabajo.");
        if (visit.getWorkStartedAt() != null || visit.getWorkFinishedAt() != null)
            throw new ValidationException("El trabajo de esta visita ya inició. Actualiza la visita.");
        if (request == null || request.mechanicId() == null)
            throw new ValidationException("Selecciona el mecánico responsable para iniciar el trabajo.");
        UUID current = visit.getMechanic() == null ? null : visit.getMechanic().getId();
        if (!Objects.equals(current, request.expectedMechanicId()))
            throw new StaleStateException("Otra sesión cambió el mecánico asignado mientras tenías el diálogo abierto. "
                + "Revisa la asignación actual antes de iniciar el trabajo.");
        boolean change = current != null && !current.equals(request.mechanicId());
        String reason = change ? requireReason(request.reason()) : null;
        UserAccount chosen = requireEligible(request.mechanicId(), SecurityUtils.getWorkshopId());
        Instant now = timings.now();
        VehicleMechanicAssignmentEvent event = null;
        if (current == null)
            event = new VehicleMechanicAssignmentEvent(visit.getId(), VehicleMechanicAssignmentEvent.Type.INITIAL_ASSIGNMENT,
                null, person(chosen), actor(), now, null);
        else if (change)
            // El nombre anterior se toma ANTES de reemplazarlo, del dato del servidor.
            event = new VehicleMechanicAssignmentEvent(visit.getId(), VehicleMechanicAssignmentEvent.Type.REASSIGNMENT,
                person(visit.getMechanic()), person(chosen), actor(), now, reason);
        visit.setMechanic(chosen);
        visit.setWorkStartedAt(now);
        visit.setStartedBy(SecurityUtils.getId());
        VehicleMovement saved = visits.saveAndFlush(visit);
        if (event != null) assignmentEvents.saveAndFlush(event);
        intervals.open(saved.getId(), VehicleWorkIntervalRepository.Origin.START, now, SecurityUtils.getId());
        return response(saved, now);
    }

    /**
     * Pausa: cierra el tramo ACTIVO abierto en el instante del servidor. El
     * tiempo activo queda congelado; inicio, mecanico y presencia no cambian.
     * Una visita en trabajo desde antes de V49 (sin tramos) escribe su unico
     * tramo historico desde el inicio real, sin perder ni duplicar tiempo.
     */
    @PreAuthorize("hasRole('POLICE')")
    public VehicleMovementResponseDTO pause(UUID id) {
        VehicleMovement visit = owned(id);
        var state = requireUnfinished(visit, "pausar");
        if (state.count() > 0 && !state.open())
            throw new ValidationException("El trabajo ya está pausado. Actualiza la visita.");
        Instant now = timings.now();
        UUID actor = SecurityUtils.getId();
        if (state.count() == 0) {
            requireNotBefore(now, visit.getWorkStartedAt());
            intervals.insertLegacyCarryover(visit.getId(), visit.getWorkStartedAt(), visit.getStartedBy(), now, actor);
        } else {
            requireNotBefore(now, state.openSince());
            if (intervals.closeOpen(visit.getId(), now, actor) != 1)
                throw new IllegalStateException("No se encontró el tramo activo de la visita " + visit.getId());
        }
        return response(visit, now);
    }

    /** Reanuda: abre un tramo ACTIVO nuevo; la pausa (el hueco) nunca cuenta como trabajo. */
    @PreAuthorize("hasRole('POLICE')")
    public VehicleMovementResponseDTO resume(UUID id) {
        VehicleMovement visit = owned(id);
        var state = requireUnfinished(visit, "reanudar");
        if (state.count() == 0 || state.open())
            throw new ValidationException("El trabajo ya está en curso. Actualiza la visita.");
        Instant now = timings.now();
        requireNotBefore(now, state.lastEndedAt());
        intervals.open(visit.getId(), VehicleWorkIntervalRepository.Origin.RESUME, now, SecurityUtils.getId());
        return response(visit, now);
    }

    /**
     * Correccion de compatibilidad (API, sin boton en la interfaz desde V49):
     * cambia la asignacion PREVIA de una visita historica que ya tenia mecanico
     * y aun no inicio. Una visita sin mecanico responde 400: su mecanico se
     * elige en {@link #start}. El flujo normal corrige una asignacion previa
     * dentro del mismo inicio (REASSIGNMENT con motivo), sin este paso extra.
     * <p>
     * La fila se bloquea con el mismo {@code FOR UPDATE} que usan inicio, pausa,
     * fin y salida: si el inicio confirma primero, esta peticion ve
     * {@code work_started_at} y se rechaza; si esta confirma primero, el inicio
     * que esperaba al mecanico anterior responde 409 sin escribir.
     * <ul>
     *   <li>visita ajena o inexistente: 404;</li>
     *   <li>vehiculo ya salio, trabajo iniciado o terminado, o visita sin mecanico: 400;</li>
     *   <li>el pedido coincide con el vigente: 200 sin escribir;</li>
     *   <li>{@code expectedMechanicId} ya no es el vigente: 409, sin escribir;</li>
     *   <li>reemplazo inactivo, de otro rol o de otro taller, o sin motivo: 400.</li>
     * </ul>
     * Un cambio real agrega un evento {@code REASSIGNMENT} (V47/V48) en esta
     * misma transaccion, con motivo y nombres de este instante.
     */
    @PreAuthorize("hasRole('POLICE')")
    public VehicleMovementResponseDTO assignMechanic(UUID id, AssignMechanicRequestDTO request) {
        if (request.unexpected() != null && !request.unexpected().isEmpty())
            throw new ValidationException("Solo se acepta el mecánico nuevo y el que estaba asignado; "
                + "los tiempos y la visita los fija el servidor.");
        VehicleMovement visit = owned(id);
        if (visit.getExit() != null)
            throw new ValidationException("El vehículo ya salió del taller; el mecánico de esta visita ya no puede cambiarse.");
        if (visit.getWorkStartedAt() != null || visit.getWorkFinishedAt() != null)
            throw new ValidationException("El trabajo ya inició; el mecánico responsable de esta visita quedó fijo.");
        // Desde V49 el mecanico de una visita nueva se elige al iniciar el trabajo: este camino
        // solo corrige la asignacion PREVIA de una visita historica, nunca crea una.
        if (visit.getMechanic() == null)
            throw new ValidationException("El mecánico de esta visita se elige al pulsar «Iniciar trabajo».");
        if (request.mechanicId() == null) throw new ValidationException("Selecciona el nuevo mecánico");
        UUID current = visit.getMechanic().getId();
        if (request.mechanicId().equals(current)) return response(visit, timings.now());
        if (!Objects.equals(current, request.expectedMechanicId()))
            throw new StaleStateException("Otra sesión cambió el mecánico asignado mientras tenías el diálogo abierto. "
                + "Revisa la asignación actual antes de cambiarla.");
        String reason = requireReason(request.reason());
        VehicleMechanicAssignmentEvent.Person actor = actor();
        // Nombres tomados AHORA, del dato confiable del servidor y dentro de la transaccion: el
        // anterior antes de reemplazarlo, el nuevo de la fila elegible bloqueada con FOR SHARE.
        VehicleMechanicAssignmentEvent.Person previous = visit.getMechanic() == null ? null : person(visit.getMechanic());
        UserAccount next = requireEligible(request.mechanicId(), SecurityUtils.getWorkshopId());
        visit.setMechanic(next);
        VehicleMovement saved = visits.saveAndFlush(visit);
        Instant now = timings.now();
        assignmentEvents.saveAndFlush(new VehicleMechanicAssignmentEvent(saved.getId(),
            VehicleMechanicAssignmentEvent.Type.REASSIGNMENT, previous, person(next), actor, now, reason));
        return response(saved, now);
    }
    /**
     * Fin definitivo. Desde ACTIVO cierra el tramo abierto en el mismo instante
     * que {@code work_finished_at}; desde PAUSADO no cierra nada ni finge una
     * reanudacion, asi que la pausa final tampoco cuenta. No registra la salida
     * del vehiculo.
     */
    @PreAuthorize("hasRole('POLICE')")
    public VehicleMovementResponseDTO finish(UUID id) {
        VehicleMovement visit = owned(id);
        if (visit.getExit() != null || visit.getWorkStartedAt() == null || visit.getWorkFinishedAt() != null)
            throw new ValidationException("Solo se puede finalizar un trabajo en curso dentro del taller. Actualiza la visita.");
        var state = intervals.state(visit.getId());
        Instant now = timings.now();
        UUID actor = SecurityUtils.getId();
        requireNotBefore(now, visit.getWorkStartedAt());
        if (state.count() == 0) {
            intervals.insertLegacyCarryover(visit.getId(), visit.getWorkStartedAt(), visit.getStartedBy(), now, actor);
        } else if (state.open()) {
            requireNotBefore(now, state.openSince());
            if (intervals.closeOpen(visit.getId(), now, actor) != 1)
                throw new IllegalStateException("No se encontró el tramo activo de la visita " + visit.getId());
        } else {
            requireNotBefore(now, state.lastEndedAt());
        }
        visit.setWorkFinishedAt(now);
        visit.setFinishedBy(actor);
        return response(visits.saveAndFlush(visit), now);
    }

    /** Respuesta con los tiempos calculados en el mismo instante de la accion. */
    VehicleMovementResponseDTO response(VehicleMovement visit, Instant now) {
        return mapper.toResponse(visit, timings.forVisit(visit.getId(), now));
    }

    private VehicleWorkIntervalRepository.State requireUnfinished(VehicleMovement visit, String action) {
        if (visit.getExit() != null)
            throw new ValidationException("El vehículo ya salió del taller; no se puede " + action + " el trabajo.");
        if (visit.getWorkStartedAt() == null)
            throw new ValidationException("El trabajo de esta visita no ha iniciado; no se puede " + action + ".");
        if (visit.getWorkFinishedAt() != null)
            throw new ValidationException("El trabajo de esta visita ya finalizó; no se puede " + action + ".");
        return intervals.state(visit.getId());
    }

    /** Un reloj del servidor que retrocede nunca produce un tramo negativo: se rechaza sin escribir. */
    private static void requireNotBefore(Instant now, Instant previous) {
        if (previous != null && now.isBefore(previous))
            throw new ValidationException("El reloj del servidor es anterior al último registro. Intenta nuevamente.");
    }

    private static void rejectUnexpected(Map<String, Object> unexpected) {
        if (unexpected != null && !unexpected.isEmpty())
            throw new ValidationException("Solo se acepta el mecánico elegido, el que estaba asignado y el motivo; "
                + "los tiempos y la visita los fija el servidor.");
    }

    /** Motivo recortado de un cambio real; vacio, solo espacios o demasiado largo es un 400. */
    static String requireReason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.isEmpty()) throw new ValidationException("Escribe el motivo del cambio de mecánico.");
        if (reason.codePointCount(0, reason.length()) > REASON_MAX)
            throw new ValidationException("El motivo no puede superar " + REASON_MAX + " caracteres.");
        return reason;
    }
    /** Formato visible de la aplicacion: "Nombre Apellido", recortado; null si la cuenta no tiene empleado. */
    static String displayName(UserAccount account) {
        var employee = account == null ? null : account.getEmployee();
        if (employee == null) return null;
        String name = (Objects.toString(employee.getFirstName(), "") + " " + Objects.toString(employee.getLastName(), "")).strip();
        return name.isEmpty() ? null : name;
    }
    private VehicleMechanicAssignmentEvent.Person person(UserAccount account) {
        return new VehicleMechanicAssignmentEvent.Person(account.getId(), displayName(account));
    }
    /** Quien registra el evento: la cuenta del token, con su nombre de este instante. */
    private VehicleMechanicAssignmentEvent.Person actor() {
        UserAccount account = currentUser.getCurrentUserAccount();
        String name = displayName(account);
        if (name == null) throw new ValidationException("El usuario autenticado no tiene un empleado asociado");
        return new VehicleMechanicAssignmentEvent.Person(account.getId(), name);
    }
    private VehicleMovement owned(UUID id) {
        return visits.lockOwned(id, SecurityUtils.getWorkshopId())
            .orElseThrow(() -> new EntityNotFoundException("No existe el registro especificado"));
    }
}
