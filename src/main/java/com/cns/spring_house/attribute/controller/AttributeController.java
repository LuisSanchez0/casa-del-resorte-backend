package com.cns.spring_house.attribute.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.attribute.dto.AttributeResponseDTO;
import com.cns.spring_house.attribute.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.attribute.dto.SearchAttributeRequestDTO;
import com.cns.spring_house.attribute.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.attribute.service.AttributeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RequestMapping("/v1/attributes")
@RestController
@RequiredArgsConstructor
public class AttributeController {
    
    private final AttributeService service;

    @GetMapping("/{id}")
    public ResponseEntity<AttributeResponseDTO> get(
            @PathVariable UUID id) {
        AttributeResponseDTO attribute = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(attribute);
    }
    
    @GetMapping
    public ResponseEntity<List<AttributeResponseDTO>> getAll(
            @Valid SearchAttributeRequestDTO requestDTO) {
        List<AttributeResponseDTO> attributes = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(attributes);
    }

    @PostMapping
    public ResponseEntity<AttributeResponseDTO> create(
            @RequestBody @Valid CreateAttributeRequestDTO requestDTO) {
        AttributeResponseDTO attribute = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(attribute);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AttributeResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateAttributeRequestDTO requestDTO) {
        AttributeResponseDTO attribute = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(attribute);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .build();
    }

}
