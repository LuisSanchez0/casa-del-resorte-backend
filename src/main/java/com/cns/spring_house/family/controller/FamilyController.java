package com.cns.spring_house.family.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.family.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.family.dto.CreateFamilyRequestDTO;
import com.cns.spring_house.family.dto.FamilyResponseDTO;
import com.cns.spring_house.family.dto.SearchFamilyRequestDTO;
import com.cns.spring_house.family.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.family.dto.UpdateFamilyRequestDTO;
import com.cns.spring_house.family.service.FamilyService;

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



@RequestMapping("/v1/families")
@RestController
@RequiredArgsConstructor
public class FamilyController {
    
    private final FamilyService service;

    @GetMapping("/{id}")
    public ResponseEntity<FamilyResponseDTO> get(
            @PathVariable UUID id) {
        FamilyResponseDTO family = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(family);
    }
    
    @GetMapping
    public ResponseEntity<List<FamilyResponseDTO>> getAll(
            SearchFamilyRequestDTO requestDTO) {
        List<FamilyResponseDTO> families = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(families);
    }

    /**
     * Las familias en las que una pieza de esta puede convertirse al
     * transformarse. El cliente la usa para saber en que familias buscar
     * productos de salida sin conocer ninguna por su nombre.
     */
    @GetMapping("/{id}/transformation-targets")
    public ResponseEntity<List<FamilyResponseDTO>> getTransformationTargets(
            @PathVariable UUID id) {
        List<FamilyResponseDTO> families = service.getTransformationTargets(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(families);
    }

    @PostMapping
    public ResponseEntity<FamilyResponseDTO> create(
            @RequestBody @Valid CreateFamilyRequestDTO requestDTO) {
        FamilyResponseDTO family = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(family);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<FamilyResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateFamilyRequestDTO requestDTO) {
        FamilyResponseDTO family = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(family);
    }

    @PostMapping("/{id}/attributes")
    public ResponseEntity<FamilyResponseDTO> create(
            @PathVariable UUID id,
            @RequestBody @Valid CreateAttributeRequestDTO requestDTO) {
        FamilyResponseDTO family = service.createAttribute(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(family);
    }

    @PatchMapping("/{id}/attributes/{attrId}")
    public ResponseEntity<FamilyResponseDTO> update(
            @PathVariable UUID id,
            @PathVariable UUID attrId,
            @RequestBody @Valid UpdateAttributeRequestDTO requestDTO) {
        FamilyResponseDTO family = service.updateAttribute(id, attrId, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(family);
    }
    
}
