package com.cns.spring_house.auth.controller;

import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.auth.dto.AuthResponseDTO;
import com.cns.spring_house.auth.dto.LoginRequestDTO;
import com.cns.spring_house.useraccount.dto.UserAccountResponseDTO;
import com.cns.spring_house.auth.service.AuthService;
import com.cns.spring_house.shared.security.CookieUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    @GetMapping("/me")
    public ResponseEntity<UserAccountResponseDTO> get() {
        UserAccountResponseDTO userAccount = authService.getUserAccount();
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(userAccount);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(
            @RequestBody @Valid LoginRequestDTO requestDTO) {
        Map<String, String> tokens = authService.login(requestDTO);
        return buildAuthResponse(tokens);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponseDTO> refresh(
            @CookieValue String refreshToken) {
        Map<String, String> tokens = authService.refresh(refreshToken);
        return buildAuthResponse(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue String refreshToken) {
        authService.logout(refreshToken);
        String refreshTokenCookie = CookieUtils
                .deleteCookie("refreshToken")
                .toString();
        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie)
                .build();
    }

    private ResponseEntity<AuthResponseDTO> buildAuthResponse(Map<String, String> tokens) {
        String accessToken = tokens.get("accessToken");
        String refreshToken = tokens.get("refreshToken");
        String refreshTokenCookie = CookieUtils
                .createCookie("refreshToken", refreshToken, Duration.ofDays(7))
                .toString();
        return ResponseEntity
                .status(HttpStatus.OK)
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie)
                .body(new AuthResponseDTO(accessToken));
    }

}
