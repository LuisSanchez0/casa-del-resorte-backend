package com.cns.spring_house.vehiclemovement.controller;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.vehiclemovement.dto.MechanicAssignmentHistoryDTO;
import com.cns.spring_house.vehiclemovement.service.VehicleMechanicHistoryService;

import lombok.RequiredArgsConstructor;

/**
 * Bitacora de asignaciones de mecanico de una visita, solo lectura. Policia la
 * ve en el detalle de la visita y Admin en el reporte de tiempos; ningun rol
 * puede modificarla por esta ni por otra ruta.
 */
@RestController
@RequestMapping("/v1/vehicle-movements")
@PreAuthorize("hasAnyRole('POLICE','ADMIN')")
@RequiredArgsConstructor
public class VehicleMechanicHistoryController {
    private final VehicleMechanicHistoryService service;

    @GetMapping("/{id}/mechanic-history")
    public MechanicAssignmentHistoryDTO history(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.history(id, page, size);
    }
}
