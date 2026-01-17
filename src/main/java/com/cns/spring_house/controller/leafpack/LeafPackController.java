package com.cns.spring_house.controller.leafpack;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dto.leafpack.CreateLeafPackRequestDTO;
import com.cns.spring_house.dto.leafpack.LeafPackResponseDTO;
import com.cns.spring_house.service.leafpack.LeafPackService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RequestMapping("/v1/leaf-packs")
@RestController
@RequiredArgsConstructor
public class LeafPackController {
    private final LeafPackService service;

    @GetMapping
    public ResponseEntity<List<LeafPackResponseDTO>> getAll() {
        List<LeafPackResponseDTO> leafPacks = service.getAll();
        return ResponseEntity
                .ok()
                .body(leafPacks);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LeafPackResponseDTO> get(
            @PathVariable UUID id) {
        LeafPackResponseDTO leafPack = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(leafPack);
    }

    @PostMapping
    public ResponseEntity<LeafPackResponseDTO> create(
            @RequestBody @Valid CreateLeafPackRequestDTO requestDTO) {
        LeafPackResponseDTO leafPack = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(leafPack);
    }

    @PostMapping("/{id}/open")
    public ResponseEntity<LeafPackResponseDTO> open(
            @PathVariable UUID id
    ) {
        LeafPackResponseDTO leafPack = service.open(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(leafPack);
    }
}
