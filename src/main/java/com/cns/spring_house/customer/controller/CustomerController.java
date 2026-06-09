package com.cns.spring_house.customer.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.customer.dto.SearchCustomerRequestDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;
import com.cns.spring_house.customer.service.CustomerService;

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


@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/customers")
public class CustomerController {

    private final CustomerService service;

    @GetMapping
    public ResponseEntity<List<CustomerResponseDTO>> getAll(
            @Valid SearchCustomerRequestDTO requestDTO) {
        List<CustomerResponseDTO> customers = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(customers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerResponseDTO> get(
            @PathVariable UUID id) {
        CustomerResponseDTO customer = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(customer);
    }

    @PostMapping("/{id}")
    public ResponseEntity<CustomerResponseDTO> get(
            @Valid CreateCustomerRequestDTO requestDTO) {
        CustomerResponseDTO customer = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(customer);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CustomerResponseDTO> get(
            @PathVariable UUID id,
            @Valid UpdateCustomerRequestDTO requestDTO) {
        CustomerResponseDTO customer = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(customer);
    }
    
}
