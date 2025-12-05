package com.cns.spring_house.controller.family;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dto.family.CreateFamilyRequestDTO;
import com.cns.spring_house.dto.family.FamilyResponseDTO;
import com.cns.spring_house.dto.family.SearchFamilyRequestDTO;
import com.cns.spring_house.dto.family.UpdateFamilyRequestDTO;
import com.cns.spring_house.service.family.FamilyService;

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
                .status(HttpStatus.CREATED)
                .body(family);
    }
    
}
