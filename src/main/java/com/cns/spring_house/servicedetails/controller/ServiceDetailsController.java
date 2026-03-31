package com.cns.spring_house.servicedetails.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.servicedetails.dto.CreateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.dto.ServiceDetailsResponseDTO;
import com.cns.spring_house.servicedetails.dto.UpdateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.service.ServiceDetailsService;

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



@RequiredArgsConstructor
@RequestMapping("/v1/services")
@RestController
public class ServiceDetailsController {
    
    private final ServiceDetailsService service;

    @GetMapping
    public ResponseEntity<List<ServiceDetailsResponseDTO>> getAll() {
        List<ServiceDetailsResponseDTO> services = service.getAll();
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(services);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceDetailsResponseDTO> get(
            @PathVariable UUID id) {
        ServiceDetailsResponseDTO serviceDetails = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(serviceDetails);
    }
    
    @PostMapping
    public ResponseEntity<ServiceDetailsResponseDTO> create(
            @RequestBody @Valid CreateServiceDetailsRequestDTO requestDTO) {
        ServiceDetailsResponseDTO serviceDetails = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(serviceDetails);
    }
    

    @PatchMapping("/{id}")
    public ResponseEntity<ServiceDetailsResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateServiceDetailsRequestDTO requestDTO) {
        ServiceDetailsResponseDTO serviceDetails = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(serviceDetails);
    }

}
