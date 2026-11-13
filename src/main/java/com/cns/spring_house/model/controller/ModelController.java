package com.cns.spring_house.model.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.dto.SearchModelRequestDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;
import com.cns.spring_house.model.service.ModelService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RequestMapping("/v1/models")
@RestController
@RequiredArgsConstructor
public class ModelController {
    
    private final ModelService service;

    @GetMapping("/{id}")
    public ResponseEntity<ModelResponseDTO> get(
            @PathVariable UUID id) {
        ModelResponseDTO model = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(model);
    }

    @GetMapping
    public ResponseEntity<List<ModelResponseDTO>> getAll(
            SearchModelRequestDTO requestDTO) {
        List<ModelResponseDTO> models = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(models);
    }

    /**
     * Alta en el catalogo GLOBAL: la misma regla que eliminar. La lectura queda
     * abierta a cualquier sesion —la caja, el taller y el historial la usan—,
     * pero solo quien administra el catalogo lo cambia.
     */
    @PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
    @PostMapping
    public ResponseEntity<ModelResponseDTO> create(
            @RequestBody @Valid CreateModelRequestDTO requestDTO ) {
        ModelResponseDTO model = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(model);
    }

    /** Editar: misma regla que dar de alta y eliminar. */
    @PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
    @PatchMapping("/{id}")
    public ResponseEntity<ModelResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateModelRequestDTO requestDTO) {
        ModelResponseDTO model = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(model);
    }

    /**
     * "Eliminar": eliminacion LOGICA y definitiva. La fila se conserva para el
     * historial y deja de listarse y de poder elegirse. No existe la operacion
     * inversa en ninguna ruta.
     *
     * 204 tambien si ya estaba eliminado (reintento o doble clic); 404 si
     * no existe. Solo quien administra el catalogo: Control, que tiene la
     * pantalla de marcas y modelos, y ADMIN, igual que proveedores.
     */
    @PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity
                .noContent()
                .build();
    }

}
