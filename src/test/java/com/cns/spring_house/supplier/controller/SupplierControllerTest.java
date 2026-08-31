package com.cns.spring_house.supplier.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.supplier.dto.CreateSupplierRequestDTO;
import com.cns.spring_house.supplier.dto.SupplierResponseDTO;
import com.cns.spring_house.supplier.service.SupplierService;

/**
 * El contrato HTTP de proveedores: que ruta responde, que llega al servicio y
 * QUIEN puede llamarlo.
 *
 * La autorizacion se comprueba aparte, en {@link Authorization}, por el mismo
 * motivo que en el panel administrativo: el `standaloneSetup` de MockMvc monta
 * el controlador SIN el proxy de seguridad, de modo que una prueba de ruta
 * pasaria igual aunque alguien borrara el `@PreAuthorize`. Ese bloque levanta un
 * contexto con `@EnableMethodSecurity` para que la anotacion este de verdad en
 * juego.
 */
@ExtendWith(MockitoExtension.class)
class SupplierControllerTest {

    @Mock
    private SupplierService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SupplierController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ── Rutas ────────────────────────────────────────────────────

    @Test
    void searchPassesTheQueryAndLimitThrough() throws Exception {
        when(service.search("resortes", 5))
                .thenReturn(List.of(new SupplierResponseDTO(
                        UUID.randomUUID(), "Resortes Guatemala")));

        mockMvc.perform(get("/v1/suppliers")
                        .param("q", "resortes")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Resortes Guatemala"));

        verify(service).search("resortes", 5);
    }

    /** Sin parametros el servicio decide el tope; el controlador no inventa uno. */
    @Test
    void searchWithoutParametersDelegatesNulls() throws Exception {
        when(service.search(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/v1/suppliers")).andExpect(status().isOk());

        verify(service).search(null, null);
    }

    @Test
    void createReturnsTheCreatedSupplier() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(
                new SupplierResponseDTO(id, "Resortes Guatemala"));

        mockMvc.perform(post("/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Resortes Guatemala\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Resortes Guatemala"));
    }

    /**
     * Un nombre en blanco se rechaza en la validacion del DTO, ANTES de llegar
     * al servicio: `@NotBlank` y no `@NotNull`, porque aqui el campo es
     * obligatorio de verdad.
     */
    @Test
    void createRejectsABlankName() throws Exception {
        mockMvc.perform(post("/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any());
    }

    /**
     * El taller NO viaja en el cuerpo. Si lo hiciera, alguien podria dar de alta
     * proveedores en el taller de otro; el servidor lo resuelve desde el token.
     */
    @Test
    void createIgnoresAnyWorkshopSentByTheClient() throws Exception {
        when(service.create(any())).thenReturn(
                new SupplierResponseDTO(UUID.randomUUID(), "Resortes Guatemala"));

        mockMvc.perform(post("/v1/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Resortes Guatemala\","
                                + "\"workshop\":\"11111111-1111-1111-1111-111111111111\"}"))
                .andExpect(status().isCreated());

        // El DTO solo tiene `name`: el taller enviado no llega a ninguna parte.
        Mockito.verify(service).create(new CreateSupplierRequestDTO("Resortes Guatemala"));
    }

    // ── Autorizacion ─────────────────────────────────────────────

    /**
     * Proveedores NO es un modulo abierto a cualquier sesion valida.
     *
     * Se abre a CONTROL, que es quien registra las entradas de inventario y por
     * tanto quien necesita elegir y crear proveedores, y a ADMIN, que lee las
     * analiticas de compras. Un vendedor o un policia con sesion valida no
     * tienen nada que hacer aqui: sin esta anotacion, la cadena de filtros solo
     * exige estar autenticado.
     */
    @Nested
    class Authorization {

        private AnnotationConfigApplicationContext context;
        private SupplierController secured;
        private SupplierService securedService;

        @BeforeEach
        void startSecuredContext() {
            context = new AnnotationConfigApplicationContext(SecuredConfig.class);
            secured = context.getBean(SupplierController.class);
            securedService = context.getBean(SupplierService.class);
        }

        @AfterEach
        void stop() {
            SecurityContextHolder.clearContext();
            context.close();
        }

        private void authenticateAs(String role) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "user", "n/a",
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        }

        @Test
        void controlAndAdminReachEveryEndpoint() {
            for (String role : List.of("CONTROL", "ADMIN")) {
                authenticateAs(role);

                assertThatCode(() -> {
                    secured.search("resortes", null);
                    secured.create(new CreateSupplierRequestDTO("Resortes Guatemala"));
                }).as("rol %s", role).doesNotThrowAnyException();
            }
        }

        @Test
        void otherAuthenticatedRolesAreDenied() {
            for (String role : List.of("SELLER", "SECRETARY", "POLICE", "OPERATOR")) {
                authenticateAs(role);

                assertThatThrownBy(() -> secured.search("resortes", null))
                        .as("busqueda con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() ->
                        secured.create(new CreateSupplierRequestDTO("Nuevo")))
                        .as("alta con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
            }

            // Denegado en la puerta: el servicio nunca llega a consultar nada.
            verify(securedService, never()).search(any(), any());
            verify(securedService, never()).create(any());
        }

        @Test
        void anonymousIsDenied() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> secured.search("resortes", null))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verify(securedService, never()).search(any(), any());
        }
    }

    /** Contexto minimo con seguridad de metodo activa, como en produccion. */
    @Configuration
    @EnableMethodSecurity
    static class SecuredConfig {

        @Bean
        SupplierService supplierService() {
            return Mockito.mock(SupplierService.class);
        }

        @Bean
        SupplierController supplierController(SupplierService service) {
            return new SupplierController(service);
        }
    }

}
