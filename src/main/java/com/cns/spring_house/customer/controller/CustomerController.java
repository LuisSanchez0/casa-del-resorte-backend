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
import org.springframework.web.bind.annotation.RequestBody;


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

    /**
     * Alta de cliente en la raiz de la coleccion: un cliente que todavia no
     * existe no tiene identificador, asi que no puede colgar de `/{id}`.
     *
     * Antes se mapeaba en `/{id}` y sin `@RequestBody`, de modo que Spring
     * enlazaba el DTO desde parametros de consulta y descartaba el JSON: la ruta
     * canonica respondia 405 y la mapeada rechazaba todo cuerpo por vacio.
     */
    @PostMapping
    public ResponseEntity<CustomerResponseDTO> create(
            @RequestBody @Valid CreateCustomerRequestDTO requestDTO) {
        CustomerResponseDTO customer = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(customer);
    }

    /**
     * `@RequestBody` no es decorativo: sin el, los campos llegaban nulos y el
     * mapeo parcial los ignoraba, asi que la peticion respondia 200 sin haber
     * cambiado nada. Una actualizacion silenciosamente vacia es peor que un error.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<CustomerResponseDTO> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateCustomerRequestDTO requestDTO) {
        CustomerResponseDTO customer = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(customer);
    }
    
}
