package com.cns.spring_house.workresult.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.workresult.dto.SearchWorkResultRequestDTO;
import com.cns.spring_house.workresult.dto.WorkResultResponseDTO;
import com.cns.spring_house.workresult.service.WorkResultService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/work-results")
public class WorkResultController {

    private final WorkResultService service;

    @GetMapping
    public ResponseEntity<List<WorkResultResponseDTO>> getAll(
            SearchWorkResultRequestDTO requestDTO) {
        List<WorkResultResponseDTO> workResults = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(workResults);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkResultResponseDTO> get(
            @PathVariable UUID id) {
        WorkResultResponseDTO workResult = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(workResult);
    }

}
