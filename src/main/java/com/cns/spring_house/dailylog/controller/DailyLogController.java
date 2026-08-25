package com.cns.spring_house.dailylog.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.dto.SearchDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.SecretaryDashboardResponseDTO;
import com.cns.spring_house.dailylog.dto.UpdateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.service.DailyLogService;
import com.cns.spring_house.dailylog.service.SecretaryDashboardService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * El prefijo `/v1` faltaba: este controller era el unico del proyecto colgando
 * de la raiz. Ningun cliente lo llamaba todavia —el frontend no tiene pantalla
 * de bitacora—, asi que alinearlo con el resto no rompe nada.
 */
@RequestMapping("/v1/daily-logs")
@RestController
@RequiredArgsConstructor
public class DailyLogController {

    private final DailyLogService service;
    private final SecretaryDashboardService dashboardService;

    /**
     * Panel de inicio de secretaria: hoy, el periodo y la serie diaria en una
     * sola peticion.
     *
     * El segmento literal `/dashboard` se resuelve antes que `/{id}` porque el
     * comparador de patrones de Spring prefiere el mas especifico, igual que en
     * {@code SaleController} y {@code VehicleMovementController}.
     *
     * `days` es el UNICO parametro admitido, y solo dimensiona la ventana. Ni
     * el taller ni el empleado pueden llegar del cliente: los resuelve el
     * servicio desde el contexto de seguridad, asi que nadie puede pedir las
     * cifras de otra persona ni de otro taller.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<SecretaryDashboardResponseDTO> getDashboard(
            @RequestParam(required = false) Integer days) {
        SecretaryDashboardResponseDTO dashboard = dashboardService.getDashboard(days);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dashboard);
    }

    /** El taller no se acepta como filtro: sale del token. */
    @GetMapping
    public ResponseEntity<List<DailyLogResponseDTO>> getAll(
            SearchDailyLogRequestDTO requestDTO) {
        List<DailyLogResponseDTO> dailyLogs = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dailyLogs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DailyLogResponseDTO> get(
            @PathVariable UUID id) {
        DailyLogResponseDTO dailyLog = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dailyLog);
    }

    @PostMapping
    public ResponseEntity<DailyLogResponseDTO> create(
            @RequestBody @Valid CreateDailyLogRequestDTO requestDTO) {
        DailyLogResponseDTO dailyLog = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(dailyLog);
    }
    
    /**
     * Solo corrige la nota general. Ni la venta ni las lineas se pueden editar:
     * son el historico, y reescribirlo desde una pantalla vaciaria de sentido
     * guardarlo. No hay DELETE, tampoco lo habia antes.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<DailyLogResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateDailyLogRequestDTO requestDTO) {
        DailyLogResponseDTO dailyLog = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dailyLog);
    }
    

}
