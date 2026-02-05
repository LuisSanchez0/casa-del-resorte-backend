package com.cns.spring_house.inventory.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.inventory.dto.AddStockRequestDTO;
import com.cns.spring_house.inventory.dto.CreateInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.SearchInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.InventoryResponseDTO;
import com.cns.spring_house.inventory.dto.UpdateInventoryRequestDTO;
import com.cns.spring_house.inventory.service.InventoryService;

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


@RequestMapping("/v1/inventories")
@RestController
@RequiredArgsConstructor
public class InventoryController {
    
    private final InventoryService service;

    @GetMapping("/{id}")
    public ResponseEntity<InventoryResponseDTO> get(
            @PathVariable UUID id) {
        InventoryResponseDTO inventory = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(inventory);
    }

    @GetMapping
    public ResponseEntity<List<InventoryResponseDTO>> getAll(
            SearchInventoryRequestDTO requestDTO) {
        List<InventoryResponseDTO> inventories = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(inventories);
    }

    @PostMapping
    public ResponseEntity<InventoryResponseDTO> create(
            @RequestBody @Valid CreateInventoryRequestDTO requestDTO) {
        InventoryResponseDTO inventory = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(inventory);
    }

    @PostMapping("/{id}/add-stock")
    public ResponseEntity<InventoryResponseDTO> postMethodName(
            @PathVariable UUID id,
            @RequestBody @Valid AddStockRequestDTO requestDTO) {
        InventoryResponseDTO inventory = service.addStock(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(inventory);
    }
    

    @PatchMapping("/{id}")
    public ResponseEntity<InventoryResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateInventoryRequestDTO requestDTO) {
        InventoryResponseDTO inventory = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(inventory);
    }
    
}