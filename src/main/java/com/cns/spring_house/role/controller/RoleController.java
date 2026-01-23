package com.cns.spring_house.role.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.role.dto.RoleResponseDTO;
import com.cns.spring_house.role.dto.SearchRoleRequestDTO;
import com.cns.spring_house.role.service.RoleService;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/roles")
public class RoleController {

    private final RoleService service;

    @GetMapping
    public ResponseEntity<List<RoleResponseDTO>> getAll(SearchRoleRequestDTO requestDTO) {
        List<RoleResponseDTO> roles = service.getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(roles);
    }

}
