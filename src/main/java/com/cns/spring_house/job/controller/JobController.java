package com.cns.spring_house.job.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.job.dto.CreateJobLogRequestDTO;
import com.cns.spring_house.job.dto.CreateJobRequestDTO;
import com.cns.spring_house.job.dto.JobLogResponseDTO;
import com.cns.spring_house.job.dto.JobResponseDTO;
import com.cns.spring_house.job.dto.SearchJobLogRequestDTO;
import com.cns.spring_house.job.dto.SearchJobRequestDTO;
import com.cns.spring_house.job.dto.UpdateJobRequestDTO;
import com.cns.spring_house.job.service.JobService;

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
@RequestMapping("/v1/jobs")
@RestController
public class JobController {
    
    private final JobService service;

    @GetMapping
    public ResponseEntity<List<JobResponseDTO>> getAll(
            SearchJobRequestDTO requestDTO) {
        List<JobResponseDTO> jobs = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(jobs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponseDTO> get(
            @PathVariable UUID id) {
        JobResponseDTO job = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(job);
    }
    
    @PostMapping
    public ResponseEntity<JobResponseDTO> create(
            @RequestBody @Valid CreateJobRequestDTO requestDTO) {
        JobResponseDTO job = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(job);
    }
    

    @PatchMapping("/{id}")
    public ResponseEntity<JobResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateJobRequestDTO requestDTO) {
        JobResponseDTO job = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(job);
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<JobLogResponseDTO> getLog(
            @PathVariable UUID id) {
        JobLogResponseDTO log = service.getLog(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(log);
    }
    
    @GetMapping("/logs")
    public ResponseEntity<List<JobLogResponseDTO>> getAllLogs(
            SearchJobLogRequestDTO requestDTO) {
        List<JobLogResponseDTO> logs = service.getAllLogs(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(logs);
    }
    
    @PostMapping("/logs")
    public ResponseEntity<JobLogResponseDTO> createLog(
                @RequestBody @Valid CreateJobLogRequestDTO requestDTO) {
        JobLogResponseDTO log = service.createLog(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(log);
    }
    
}
