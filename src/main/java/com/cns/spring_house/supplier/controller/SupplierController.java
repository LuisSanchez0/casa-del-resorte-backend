package com.cns.spring_house.supplier.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.supplier.dto.CreateSupplierRequestDTO;
import com.cns.spring_house.supplier.dto.SupplierResponseDTO;
import com.cns.spring_house.supplier.service.SupplierService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Proveedores del taller autenticado.
 *
 * NINGUN endpoint acepta un identificador de taller, igual que el panel
 * administrativo: el alcance sale del token, de modo que no hay que comprobar
 * despues que el taller pedido coincida con el propio porque nunca hay un
 * taller pedido.
 *
 * Autorizacion por ROL a nivel de clase. La cadena de filtros solo exige estar
 * autenticado (`anyRequest().authenticated()`), asi que sin esto un policia o un
 * vendedor podria listar —y dar de alta— proveedores. Se abre a CONTROL, que es
 * quien registra las entradas de inventario y por tanto quien necesita elegir y
 * crear proveedores, y a ADMIN, que es quien lee las analiticas de compras. El
 * prefijo `ROLE_` lo pone {@code JwtConfig} al convertir la claim, de ahi
 * `hasAnyRole` y no `hasAnyAuthority`.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('CONTROL', 'ADMIN')")
@RequestMapping("/v1/suppliers")
public class SupplierController {

    private final SupplierService service;

    /**
     * Autocompletado. `q` es texto libre y viaja como LITERAL: los comodines de
     * LIKE que contenga se neutralizan en el servicio, no actuan como sintaxis.
     *
     * `limit` lo valida el servicio y responde 400 con mensaje de negocio si se
     * pide un tamaño fuera de rango, en vez de recortar la lista en silencio.
     */
    @GetMapping
    public ResponseEntity<List<SupplierResponseDTO>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(service.search(q, limit));
    }

    @PostMapping
    public ResponseEntity<SupplierResponseDTO> create(
            @RequestBody @Valid CreateSupplierRequestDTO requestDTO) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(service.create(requestDTO));
    }

}
