package com.cns.spring_house.model.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.cns.spring_house.brand.controller.BrandController;
import com.cns.spring_house.brand.service.BrandService;
import com.cns.spring_house.model.service.ModelService;

/**
 * QUIEN puede eliminar una marca o un modelo.
 *
 * Solo quien administra el catalogo: Control, que tiene la pantalla de marcas y
 * modelos, y ADMIN, como en proveedores. Ni la caja, ni el taller, ni la
 * policia, ni la secretaria.
 *
 * Marcas y modelos son un catalogo GLOBAL —las tablas no tienen taller—, asi
 * que no hay un alcance por taller que comprobar aqui, solo el rol.
 *
 * Contexto con `@EnableMethodSecurity` real, como el resto de pruebas de
 * autorizacion: sin el proxy, la prueba pasaria aunque se borrara el
 * `@PreAuthorize`.
 */
class CatalogDeletionAuthorizationTest {

    private static final List<String> CATALOG_ROLES = List.of("CONTROL", "ADMIN");

    private static final List<String> OTHER_ROLES =
            List.of("SELLER", "OPERATOR", "POLICE", "SECRETARY");

    private AnnotationConfigApplicationContext context;
    private BrandController brands;
    private ModelController models;
    private BrandService brandService;
    private ModelService modelService;

    @BeforeEach
    void startSecuredContext() {
        context = new AnnotationConfigApplicationContext(SecuredConfig.class);
        brands = context.getBean(BrandController.class);
        models = context.getBean(ModelController.class);
        brandService = context.getBean(BrandService.class);
        modelService = context.getBean(ModelService.class);
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
    void catalogAdministratorsCanDeleteAndGetNoContent() {
        for (String role : CATALOG_ROLES) {
            authenticateAs(role);

            assertThat(brands.delete(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(models.delete(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        }
    }

    @Test
    void everyOtherRoleIsDeniedAndNeverReachesTheService() {
        for (String role : OTHER_ROLES) {
            authenticateAs(role);

            assertThatThrownBy(() -> brands.delete(UUID.randomUUID()))
                    .as("eliminar marca con rol %s", role)
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> models.delete(UUID.randomUUID()))
                    .as("eliminar modelo con rol %s", role)
                    .isInstanceOf(AccessDeniedException.class);
        }

        verify(brandService, never()).delete(any());
        verify(modelService, never()).delete(any());
    }

    @Test
    void anonymousIsDenied() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> brands.delete(UUID.randomUUID()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> models.delete(UUID.randomUUID()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verify(brandService, never()).delete(any());
        verify(modelService, never()).delete(any());
    }

    @Configuration
    @EnableMethodSecurity
    static class SecuredConfig {

        @Bean
        BrandService brandService() {
            return Mockito.mock(BrandService.class);
        }

        @Bean
        ModelService modelService() {
            return Mockito.mock(ModelService.class);
        }

        @Bean
        BrandController brandController(BrandService service) {
            return new BrandController(service);
        }

        @Bean
        ModelController modelController(ModelService service) {
            return new ModelController(service);
        }
    }
}
