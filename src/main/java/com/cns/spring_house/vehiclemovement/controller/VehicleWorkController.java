package com.cns.spring_house.vehiclemovement.controller;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.cns.spring_house.vehiclemovement.dto.*;
import com.cns.spring_house.vehiclemovement.service.VehicleWorkService;
/**
 * Ciclo del trabajo de UNA visita, solo Policia y solo en su taller:
 * NOT_STARTED --start(mecanico)--> ACTIVE --pause--> PAUSED --resume--> ACTIVE;
 * ACTIVE o PAUSED --finish--> FINISHED. Ninguna accion acepta fechas,
 * duraciones ni taller del cliente: todo instante es del servidor.
 */
@RestController
@RequestMapping("/v1/vehicle-movements")
@PreAuthorize("hasRole('POLICE')")
@RequiredArgsConstructor
public class VehicleWorkController {
    private final VehicleWorkService service;
    @GetMapping("/mechanics")
    public List<MechanicDTO> mechanics(@RequestParam(defaultValue="") String search) { return service.search(search); }
    /** Inicia y asigna al mecanico responsable en una sola transaccion; ver {@link VehicleWorkService#start}. */
    @PostMapping("/{id}/start-work")
    public VehicleMovementResponseDTO start(@PathVariable UUID id, @RequestBody(required=false) StartWorkRequestDTO body) {
        return service.start(id, body);
    }
    /** Correccion de una asignacion previa historica; ver {@link VehicleWorkService#assignMechanic}. */
    @PatchMapping("/{id}/mechanic")
    public VehicleMovementResponseDTO assignMechanic(@PathVariable UUID id, @RequestBody AssignMechanicRequestDTO body) {
        return service.assignMechanic(id, body);
    }
    @PostMapping("/{id}/pause-work")
    public VehicleMovementResponseDTO pause(@PathVariable UUID id, @RequestBody(required=false) String body) {
        rejectBody(body); return service.pause(id);
    }
    @PostMapping("/{id}/resume-work")
    public VehicleMovementResponseDTO resume(@PathVariable UUID id, @RequestBody(required=false) String body) {
        rejectBody(body); return service.resume(id);
    }
    @PostMapping("/{id}/finish-work")
    public VehicleMovementResponseDTO finish(@PathVariable UUID id, @RequestBody(required=false) String body) {
        rejectBody(body); return service.finish(id);
    }
    private void rejectBody(String body) {
        if (body != null && !body.isBlank()) throw new com.cns.spring_house.shared.exception.ValidationException(
            "No se aceptan fechas ni datos del cliente para registrar tiempos de trabajo");
    }
}
