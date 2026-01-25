package com.cns.spring_house.shared.config.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON.toString());
        response.getWriter().write(
                buildResponse(request.getRequestURI()));
    }

    private String buildResponse(String path) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("detail", "Sin permisos para acceder a este recurso");
        response.put("instance", path);
        response.put("status", "403");
        response.put("title", "Authorization Error");
        response.put("error_category", "Auth");
        response.put("timestamp", Instant.now().toString());
        return objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(response);
    }

}
