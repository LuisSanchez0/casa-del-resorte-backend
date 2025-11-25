package com.cns.spring_house.controller.role;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dto.role.RoleResponseDTO;
import com.cns.spring_house.dto.role.SearchRoleRequestDTO;
import com.cns.spring_house.service.role.RoleService;

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
