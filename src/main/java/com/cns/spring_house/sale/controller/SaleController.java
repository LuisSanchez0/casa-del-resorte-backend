package com.cns.spring_house.sale.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;
import com.cns.spring_house.sale.service.SaleService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;



@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/sales")
public class SaleController {

    private final SaleService service;

    @GetMapping
    public ResponseEntity<List<SaleResponseDTO>> getAll(
            SearchSaleRequestDTO requestDTO) {
        List<SaleResponseDTO> sales = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sales);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SaleResponseDTO> get(
            @PathVariable UUID id) {
        SaleResponseDTO sale = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    @PostMapping
    public ResponseEntity<SaleResponseDTO> create(
            @RequestBody @Valid CreateSaleRequestDTO requestDTO) {
        SaleResponseDTO sale = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(sale);
    }

    @PostMapping("/{id}/add-detail")
    public ResponseEntity<SaleResponseDTO> addDetail(
            @PathVariable UUID id,
            @RequestBody AddSaleDetailRequestDTO requestDTO) {
        SaleResponseDTO sale = service.addDetail(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }

    @PostMapping("/{saleDetailId}/remove-detail")
    public ResponseEntity<SaleResponseDTO> postMethodName(
            @PathVariable UUID saleDetailId) {
        SaleResponseDTO sale = service.removeDetail(saleDetailId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
    }
    
    @PostMapping("/{id}/complete")
    public ResponseEntity<SaleResponseDTO> complete(
            @PathVariable UUID id) {
        SaleResponseDTO sale = service.completeSale(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(sale);
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
