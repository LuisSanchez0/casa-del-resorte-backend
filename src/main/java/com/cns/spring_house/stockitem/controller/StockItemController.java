package com.cns.spring_house.stockitem.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.stockitem.dto.CreateStockItemRequestDTO;
import com.cns.spring_house.stockitem.dto.SearchStockItemRequestDTO;
import com.cns.spring_house.stockitem.dto.StockItemResponseDTO;
import com.cns.spring_house.stockitem.dto.UpdateStockItemRequestDTO;
import com.cns.spring_house.stockitem.service.StockItemService;

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


@RequestMapping("/v1/stock-items")
@RestController
@RequiredArgsConstructor
public class StockItemController {
    
    private final StockItemService service;

    @GetMapping("/{id}")
    public ResponseEntity<StockItemResponseDTO> get(
            @PathVariable UUID id) {
        StockItemResponseDTO stockItem = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(stockItem);
    }

    @GetMapping
    public ResponseEntity<List<StockItemResponseDTO>> getAll(
            SearchStockItemRequestDTO requestDTO) {
        List<StockItemResponseDTO> stockItems = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(stockItems);
    }

    @PostMapping
    public ResponseEntity<StockItemResponseDTO> create(
            @RequestBody @Valid CreateStockItemRequestDTO requestDTO) {
        StockItemResponseDTO stockItem = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(stockItem);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<StockItemResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateStockItemRequestDTO requestDTO) {
        StockItemResponseDTO stockItem = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(stockItem);
    }
    
}