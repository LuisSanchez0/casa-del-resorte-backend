package com.cns.spring_house.controller.employee;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dto.employee.CreateEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.EmployeeResponseDTO;
import com.cns.spring_house.dto.employee.SearchEmployeeRequestDTO;
import com.cns.spring_house.dto.employee.UpdateEmployeeRequestDTO;
import com.cns.spring_house.service.employee.EmployeeService;

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

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/employees")
public class EmployeeController {

    private final EmployeeService service;

    @GetMapping
    public ResponseEntity<List<EmployeeResponseDTO>> getAll(
            SearchEmployeeRequestDTO requestDTO) {
        List<EmployeeResponseDTO> employees = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(employees);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeResponseDTO> get(
            @PathVariable UUID id) {
        EmployeeResponseDTO employee = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(employee);
    }

    @PostMapping
    public ResponseEntity<EmployeeResponseDTO> create(
            @RequestBody @Valid CreateEmployeeRequestDTO requestDTO) {
        EmployeeResponseDTO employee = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(employee);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EmployeeResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateEmployeeRequestDTO requestDTO) {
        EmployeeResponseDTO employee = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(employee);
    }

}
