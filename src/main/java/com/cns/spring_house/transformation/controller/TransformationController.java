package com.cns.spring_house.transformation.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.SearchTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.service.TransformationService;

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

@RequestMapping("/transformations")
@RestController
@RequiredArgsConstructor
public class TransformationController {
    
    private final TransformationService service;

    @GetMapping
    public ResponseEntity<List<TransformationResponseDTO>> getAll(
            SearchTransformationRequestDTO requestDTO) {
        List<TransformationResponseDTO> transformations = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(transformations);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransformationResponseDTO> get(
            @PathVariable UUID id) {
        TransformationResponseDTO transformation = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(transformation);
    }
    
    @PostMapping
    public ResponseEntity<TransformationResponseDTO> create(
            @RequestBody @Valid CreateTransformationRequestDTO requestDTO) {
        TransformationResponseDTO transformation = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(transformation);
    }
    
}
