package com.cns.spring_house.sale.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.inventory.dto.InventoryItemResponseDTO;
import com.cns.spring_house.sale.dto.BlockFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.FulfillTransformationRequestDTO;
import com.cns.spring_house.sale.dto.FulfillmentSourceDTO;
import com.cns.spring_house.sale.dto.FulfillmentTaskResponseDTO;
import com.cns.spring_house.sale.dto.ReleaseFulfillmentRequestDTO;
import com.cns.spring_house.sale.entity.FulfillmentArea;
import com.cns.spring_house.sale.entity.FulfillmentStatus;
import com.cns.spring_house.sale.service.SaleFulfillmentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Solicitudes de la comanda, del lado de quien las cumple.
 *
 * QUIEN puede hacer que, por metodo y no por controlador:
 *
 * <ul>
 * <li>CONTROL entrega piezas que ya existen ({@code /control},
 * {@code /eligible-items}, {@code /release}).</li>
 * <li>OPERATOR fabrica piezas y ejecuta servicios ({@code /operator},
 * {@code /start}, {@code /block}, {@code /sources}, {@code /transform},
 * {@code /complete-service}).</li>
 * <li>ADMIN puede todo lo anterior, como supervision.</li>
 * </ul>
 *
 * El vendedor NO esta en ninguna: crea y envia la comanda y la sigue desde la
 * propia venta, pero no puede dar por entregada ni por fabricada una solicitud.
 * Ninguna de estas operaciones toca precio, identidad fiscal ni cobro.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/fulfillments")
public class SaleFulfillmentController {

    private final SaleFulfillmentService service;

    /** "Pedidos por liberar". Sin `status`, lo pendiente de Control. */
    @PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
    @GetMapping("/control")
    public ResponseEntity<List<FulfillmentTaskResponseDTO>> getControlQueue(
            @RequestParam(required = false) List<FulfillmentStatus> status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page) {
        return ResponseEntity.ok(service.getQueue(FulfillmentArea.CONTROL, status, limit, page));
    }

    /** "Pedidos del taller". Sin `status`, lo pendiente del Operador. */
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @GetMapping("/operator")
    public ResponseEntity<List<FulfillmentTaskResponseDTO>> getOperatorQueue(
            @RequestParam(required = false) List<FulfillmentStatus> status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page) {
        return ResponseEntity.ok(service.getQueue(FulfillmentArea.OPERATOR, status, limit, page));
    }

    @PreAuthorize("hasAnyRole('CONTROL', 'OPERATOR', 'ADMIN')")
    @GetMapping("/{id}")
    public ResponseEntity<FulfillmentTaskResponseDTO> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    // ── Control ──────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
    @GetMapping("/{id}/eligible-items")
    public ResponseEntity<List<InventoryItemResponseDTO>> getEligibleItems(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getEligibleItems(id));
    }

    @PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
    @PostMapping("/{id}/release")
    public ResponseEntity<FulfillmentTaskResponseDTO> release(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid ReleaseFulfillmentRequestDTO requestDTO) {
        return ResponseEntity.ok(service.release(id,
                requestDTO == null ? new ReleaseFulfillmentRequestDTO(null) : requestDTO));
    }

    // ── Operador ─────────────────────────────────────────────────

    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @PostMapping("/{id}/start")
    public ResponseEntity<FulfillmentTaskResponseDTO> start(@PathVariable UUID id) {
        return ResponseEntity.ok(service.start(id));
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @PostMapping("/{id}/block")
    public ResponseEntity<FulfillmentTaskResponseDTO> block(
            @PathVariable UUID id,
            @RequestBody @Valid BlockFulfillmentRequestDTO requestDTO) {
        return ResponseEntity.ok(service.block(id, requestDTO));
    }

    /** `q` busca entre las piezas elegibles; nunca amplia lo elegible. */
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @GetMapping("/{id}/sources")
    public ResponseEntity<List<FulfillmentSourceDTO>> getSources(
            @PathVariable UUID id,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(service.getSources(id, q));
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @PostMapping("/{id}/transform")
    public ResponseEntity<FulfillmentTaskResponseDTO> transform(
            @PathVariable UUID id,
            @RequestBody @Valid FulfillTransformationRequestDTO requestDTO) {
        return ResponseEntity.ok(service.transform(id, requestDTO));
    }

    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    @PostMapping("/{id}/complete-service")
    public ResponseEntity<FulfillmentTaskResponseDTO> completeService(@PathVariable UUID id) {
        return ResponseEntity.ok(service.completeService(id));
    }

}
