package com.cns.spring_house.controller.useraccount;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.dto.useraccount.CreateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.SearchUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UpdateUserAccountRequestDTO;
import com.cns.spring_house.dto.useraccount.UserAccountReponseDTO;
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
    public ResponseEntity<List<UserAccountReponseDTO>> getAll(
            SearchUserAccountRequestDTO requestDTO) {
        List<UserAccountReponseDTO> userAccounts = service
                .getAll(requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(userAccounts);
    }

    @GetMapping("/me")
    public ResponseEntity<UserAccountReponseDTO> get() {
        UserAccountReponseDTO userAccount = service.get();
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(userAccount);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserAccountReponseDTO> get(
            @PathVariable UUID id) {
        UserAccountReponseDTO userAccount = service.get(id);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(userAccount);
    }

    @PostMapping
    public ResponseEntity<UserAccountReponseDTO> create(
            @RequestBody @Valid CreateUserAccountRequestDTO requestDTO) {
        UserAccountReponseDTO userAccount = service.create(requestDTO);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(userAccount);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserAccountReponseDTO> create(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateUserAccountRequestDTO requestDTO) {
        UserAccountReponseDTO userAccount = service.update(id, requestDTO);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(userAccount);
    }

}
