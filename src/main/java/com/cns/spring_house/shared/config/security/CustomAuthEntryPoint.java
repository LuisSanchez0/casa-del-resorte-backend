package com.cns.spring_house.shared.config.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.cns.spring_house.shared.exception.ExceptionUtils;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CustomAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON.toString());
        String message = resolveMessage(ExceptionUtils.getRootCause(authException));
        response.getWriter().write(
                buildResponse(message, request.getRequestURI()));
    }

    private String resolveMessage(Throwable cause) {
        switch (cause) {
            case JwtValidationException e:
                return "Token expirado";
            case BadJwtException e:
                return "Token inválido";
            default:
                return "No autenticado";
        }
    }

    private String buildResponse(String detail, String path) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("detail", detail);
        response.put("instance", path);
        response.put("status", "401");
        response.put("title", "Authentication Error");
        response.put("error_category", "Auth");
        response.put("timestamp", Instant.now().toString());
        return objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(response);
    }

}
