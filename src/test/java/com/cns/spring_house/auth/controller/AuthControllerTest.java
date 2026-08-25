package com.cns.spring_house.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.auth.service.AuthService;
import com.cns.spring_house.shared.config.security.SecurityProperties;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.security.CookieUtils;

import jakarta.servlet.http.Cookie;

/**
 * Contrato de errores de la renovacion de sesion.
 *
 * Sin cookie, `@CookieValue` lanza `MissingRequestCookieException`, que caia en
 * el handler generico y devolvia 500: el cliente no podia distinguir "no hay
 * sesion" de "el servidor esta roto", y un 500 no debe cerrar sesion. Las cuatro
 * formas de no poder renovar —cookie ausente, invalida, vencida o ya consumida—
 * convergen ahora en 401.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String REFRESH = "/v1/auth/refresh";
    private static final String LOGOUT = "/v1/auth/logout";

    /** Rastros de Spring que no deben viajar al cliente. */
    private static final String[] INTERNALS = {
        "required cookie", "method parameter", "org.springframework", "com.cns", "refreshtoken",
    };

    @Mock
    private AuthService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.getRefreshToken().setExpirationTime(604800000L);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(service, new CookieUtils(properties), properties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private void assertHasNoInternals(String body) {
        String normalized = body.toLowerCase(Locale.ROOT);
        for (String forbidden : INTERNALS) {
            assertThat(normalized)
                    .as("el cuerpo no debe contener '%s'", forbidden)
                    .doesNotContain(forbidden);
        }
    }

    @Nested
    @DisplayName("POST /v1/auth/refresh")
    class Refresh {

        // ── A · sin cookie ──────────────────────────────────────

        @Test
        void answersUnauthorizedWhenTheCookieIsMissing() throws Exception {
            MvcResult result = perform(post(REFRESH));

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getStatus()).isNotEqualTo(500);
        }

        @Test
        void doesNotLeakTheSpringMessageWhenTheCookieIsMissing() throws Exception {
            MvcResult result = perform(post(REFRESH));

            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("No existe una sesión válida");
            assertHasNoInternals(body);
        }

        // ── B · cookie invalida, consumida o vencida ────────────

        @Test
        void answersUnauthorizedWhenTheStoredTokenIsGone() throws Exception {
            when(service.refresh("consumida"))
                    .thenThrow(new BadCredentialsException("El token es invalido"));

            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", "consumida")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("El token es invalido"));
        }

        // ── C · cookie valida ───────────────────────────────────

        @Test
        void rotatesTheSessionWhenTheCookieIsValid() throws Exception {
            when(service.refresh("vigente")).thenReturn(
                    Map.of("accessToken", "access-rotado", "refreshToken", "refresh-rotado"));

            mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", "vigente")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-rotado"))
                    .andExpect(header().string("Set-Cookie",
                            Matchers.containsString("refreshToken=refresh-rotado")));
        }

        /** Un fallo real del servidor sigue siendo 500 y no se disfraza de 401. */
        @Test
        void keepsInternalServerErrorForRealFailures() throws Exception {
            when(service.refresh(any())).thenThrow(new IllegalStateException("Redis caido"));

            MvcResult result = perform(post(REFRESH).cookie(new Cookie("refreshToken", "vigente")));

            assertThat(result.getResponse().getStatus()).isEqualTo(500);
            assertHasNoInternals(result.getResponse().getContentAsString());
        }

    }

    @Nested
    @DisplayName("POST /v1/auth/logout")
    class Logout {

        // ── D · sin cookie ──────────────────────────────────────

        @Test
        void neverAnswersInternalServerErrorWhenTheCookieIsMissing() throws Exception {
            MvcResult result = perform(post(LOGOUT));

            assertThat(result.getResponse().getStatus()).isNotEqualTo(500);
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void closesTheSessionWhenTheCookieIsPresent() throws Exception {
            mockMvc.perform(post(LOGOUT).cookie(new Cookie("refreshToken", "vigente")))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Set-Cookie",
                            Matchers.containsString("Max-Age=0")));

            verify(service).logout("vigente");
        }

    }

}
