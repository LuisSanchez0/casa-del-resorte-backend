package com.cns.spring_house.dailylog.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dailylog.dto.CreateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.DailyLogResponseDTO;
import com.cns.spring_house.dailylog.dto.SearchDailyLogRequestDTO;
import com.cns.spring_house.dailylog.dto.UpdateDailyLogRequestDTO;
import com.cns.spring_house.dailylog.service.DailyLogService;

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

@RequestMapping("/daily-logs")
@RestController
@RequiredArgsConstructor
public class DailyLogController {
    
    private final DailyLogService service;

    @GetMapping
    public ResponseEntity<List<DailyLogResponseDTO>> getAll(
            SearchDailyLogRequestDTO requestDTO) {
        List<DailyLogResponseDTO> dailyLogs = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dailyLogs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DailyLogResponseDTO> get(
            @PathVariable UUID id) {
        DailyLogResponseDTO dailyLog = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dailyLog);
    }

    @PostMapping
    public ResponseEntity<DailyLogResponseDTO> create(
            @RequestBody @Valid CreateDailyLogRequestDTO requestDTO) {
        DailyLogResponseDTO dailyLog = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(dailyLog);
    }
    
    @PatchMapping("/{id}")
    public ResponseEntity<DailyLogResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateDailyLogRequestDTO requestDTO) {
        DailyLogResponseDTO dailyLog = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(dailyLog);
    }
    

}
