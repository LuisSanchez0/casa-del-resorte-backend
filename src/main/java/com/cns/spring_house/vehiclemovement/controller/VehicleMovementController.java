package com.cns.spring_house.vehiclemovement.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionResponseDTO;
import com.cns.spring_house.vehiclemovement.dto.PoliceDashboardResponseDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.UpdateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;
import com.cns.spring_house.vehiclemovement.service.PoliceDashboardService;
import com.cns.spring_house.vehiclemovement.service.VehicleMovementService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@RequiredArgsConstructor
@RequestMapping("/v1/vehicle-movements")
@RestController
public class VehicleMovementController {

    private final VehicleMovementService service;
    private final PoliceDashboardService dashboardService;

    /**
     * Estadisticas agregadas del taller autenticado, en una sola peticion.
     *
     * El segmento literal `/dashboard` se resuelve antes que `/{id}`: el
     * comparador de patrones de Spring prefiere el mas especifico, asi que la
     * ruta no colisiona con la busqueda por identificador.
     *
     * No admite parametros a proposito. Ni el taller ni el empleado del ambito
     * personal pueden llegar del cliente: los resuelve el servicio desde el
     * contexto de seguridad.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<PoliceDashboardResponseDTO> getDashboard() {
        PoliceDashboardResponseDTO dashboard = dashboardService.getDashboard();
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dashboard);
    }

    /**
     * Telefono que se sugerira al abrir una bitacora nueva para esa placa.
     *
     * Igual que `/dashboard`, el segmento literal se resuelve antes que `/{id}`:
     * Spring prefiere el patron mas especifico, asi que no colisiona con la
     * busqueda por identificador.
     *
     * NO acepta taller, y esa ausencia es la garantia de aislamiento: el servicio
     * lo toma del token, de modo que nadie puede pedir el telefono que otro
     * taller registro para una placa manipulando la URL.
     *
     * `@PreAuthorize("hasRole('POLICE')")` sobre este metodo y solo sobre este.
     * La cadena de filtros unicamente exige estar autenticado, asi que sin esto
     * cualquier rol con sesion valida podria consultar telefonos placa por placa.
     * Es Policia quien registra entradas, asi que es el unico rol que necesita la
     * sugerencia; Admin ve el telefono en el historial, que es otra cosa, y no
     * abre bitacoras. El resto del controller se deja como estaba: anotar
     * endpoints ajenos es un cambio de alcance distinto, con su propio riesgo.
     *
     * Una placa sin telefono previo responde 200 con `phone: null`. No es un 404:
     * "esta placa no tiene telefono registrado" es una respuesta valida a una
     * pregunta valida, y tratarla como error obligaria al cliente a distinguir un
     * fallo real de una ausencia normal.
     */
    @PreAuthorize("hasRole('POLICE')")
    @GetMapping("/phone-suggestion")
    public ResponseEntity<PhoneSuggestionResponseDTO> getPhoneSuggestion(
            @Valid PhoneSuggestionRequestDTO requestDTO) {
        PhoneSuggestionResponseDTO suggestion = service.getPhoneSuggestion(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(suggestion);
    }

    @GetMapping
    public ResponseEntity<List<VehicleMovementResponseDTO>> getAll(
            SearchVehicleMovementRequestDTO requestDTO) {
        List<VehicleMovementResponseDTO> movements = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(movements);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VehicleMovementResponseDTO> get(
            @PathVariable UUID id) {
        VehicleMovementResponseDTO movement = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(movement);
    }

    @PostMapping
    public ResponseEntity<VehicleMovementResponseDTO> create(
            @ModelAttribute @Valid CreateVehicleMovementRequestDTO requestDTO) {
        VehicleMovementResponseDTO movement = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(movement);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<VehicleMovementResponseDTO> update(
            @PathVariable UUID id,
            @ModelAttribute @Valid UpdateVehicleMovementRequestDTO requestDTO) {
        VehicleMovementResponseDTO movement = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(movement);
    }

    @PostMapping("/{id}/register-exit")
    public ResponseEntity<VehicleMovementResponseDTO> registerExit(
            @PathVariable UUID id) {
        VehicleMovementResponseDTO movement = service.registerExit(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(movement);
    }

}
