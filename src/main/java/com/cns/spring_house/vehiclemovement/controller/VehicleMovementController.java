package com.cns.spring_house.vehiclemovement.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.vehiclemovement.dto.CreateVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.SearchVehicleMovementRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.VehicleMovementResponseDTO;
import com.cns.spring_house.vehiclemovement.service.VehicleMovementService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RequiredArgsConstructor
@RequestMapping("/v1/vehicle-movements")
@RestController
public class VehicleMovementController {
    
    private final VehicleMovementService service;

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
            @RequestBody @Valid CreateVehicleMovementRequestDTO requestDTO) {
        VehicleMovementResponseDTO movement = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
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
