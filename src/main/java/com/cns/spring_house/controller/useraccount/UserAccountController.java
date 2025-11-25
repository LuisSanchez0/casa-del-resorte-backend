package com.cns.spring_house.controller.useraccount;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dto.useraccount.CreateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.SearchUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UpdateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountResponseDTO;
import com.cns.spring_house.service.useraccount.UserAccountService;

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
@RequestMapping("/v1/user-accounts")
public class UserAccountController {

        private final UserAccountService service;

        @GetMapping
        public ResponseEntity<List<UserAccountResponseDTO>> getAll(
                        SearchUserAccountRequestDTO requestDTO) {
                List<UserAccountResponseDTO> userAccounts = service
                                .getAll(requestDTO);
                return ResponseEntity
                                .status(HttpStatus.OK)
                                .body(userAccounts);
        }

        @GetMapping("/{id}")
        public ResponseEntity<UserAccountResponseDTO> get(
                        @PathVariable UUID id) {
                UserAccountResponseDTO userAccount = service.get(id);
                return ResponseEntity
                                .status(HttpStatus.OK)
                                .body(userAccount);
        }

        @PostMapping
        public ResponseEntity<UserAccountResponseDTO> create(
                        @RequestBody @Valid CreateUserAccountRequestDTO requestDTO) {
                UserAccountResponseDTO userAccount = service.create(requestDTO);
                return ResponseEntity
                                .status(HttpStatus.CREATED)
                                .body(userAccount);
        }

        @PatchMapping("/{id}")
        public ResponseEntity<UserAccountResponseDTO> create(
                        @PathVariable UUID id,
                        @RequestBody @Valid UpdateUserAccountRequestDTO requestDTO) {
                UserAccountResponseDTO userAccount = service.update(id, requestDTO);
                return ResponseEntity
                                .status(HttpStatus.OK)
                                .body(userAccount);
        }

}
