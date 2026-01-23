package com.cns.spring_house.brand.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.brand.dto.BrandResponseDTO;
import com.cns.spring_house.brand.dto.CreateBrandRequestDTO;
import com.cns.spring_house.brand.dto.SearchBrandRequestDTO;
import com.cns.spring_house.brand.dto.UpdateBrandRequestDTO;
import com.cns.spring_house.brand.service.BrandService;

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

@RequestMapping("/v1/brands")
@RestController
@RequiredArgsConstructor
public class BrandController {
    
    private final BrandService service;

    @GetMapping("/{id}")
    public ResponseEntity<BrandResponseDTO> get(
            @PathVariable UUID id) {
        BrandResponseDTO brand = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(brand);
    }
    
    @GetMapping
    public ResponseEntity<List<BrandResponseDTO>> getAll(
            SearchBrandRequestDTO requestDTO) {
        List<BrandResponseDTO> brands = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(brands);
    }

    @PostMapping
    public ResponseEntity<BrandResponseDTO> create(
            @ModelAttribute @Valid CreateBrandRequestDTO requestDTO) {
        BrandResponseDTO brand = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(brand);
    }
    
    @PatchMapping("/{id}")
    public ResponseEntity<BrandResponseDTO> update(
            @PathVariable UUID id,
            @ModelAttribute @Valid UpdateBrandRequestDTO requestDTO) {
        BrandResponseDTO brand = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(brand);
    }

}
