package com.cns.spring_house.product.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.product.dto.CreateProductRequestDTO;
import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.dto.SearchProductRequestDTO;
import com.cns.spring_house.product.dto.UpdateProductRequestDTO;
import com.cns.spring_house.product.service.ProductService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RequestMapping("/v1/products")
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> get(
            @PathVariable UUID id) {
        ProductResponseDTO product = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(product);
    }
    

    @GetMapping
    public ResponseEntity<List<ProductResponseDTO>> getAll(
            @RequestParam(required = false) Map<String, Object> filters) {
        List<ProductResponseDTO> products = service.getAll(
                SearchProductRequestDTO.from(filters));
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(products);
    }
    

    @PostMapping
    public ResponseEntity<ProductResponseDTO> create(
            @RequestBody @Valid CreateProductRequestDTO requestDTO) {
        ProductResponseDTO product = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(product);
    }
    
    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody UpdateProductRequestDTO requestDTO) {
        ProductResponseDTO product = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(product);
    }
    
}
