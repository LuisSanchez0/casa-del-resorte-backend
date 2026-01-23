package com.cns.spring_house.workshop.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.workshop.dto.CreateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.SearchWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.UpdateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;
import com.cns.spring_house.workshop.service.WorkshopService;

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

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/workshops")
public class WorkshopController {

    private final WorkshopService service;

    @GetMapping
    public ResponseEntity<List<WorkshopResponseDTO>> getAll(
            SearchWorkshopRequestDTO requestDTO) {
        List<WorkshopResponseDTO> workshops = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(workshops);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkshopResponseDTO> get(
            @PathVariable UUID id) {
        WorkshopResponseDTO workshop = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(workshop);
    }

    @PostMapping
    public ResponseEntity<WorkshopResponseDTO> create(
            @RequestBody @Valid CreateWorkshopRequestDTO requestDTO) {
        WorkshopResponseDTO workshop = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(workshop);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkshopResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateWorkshopRequestDTO requestDTO) {
        WorkshopResponseDTO workshop = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(workshop);
    }

}
