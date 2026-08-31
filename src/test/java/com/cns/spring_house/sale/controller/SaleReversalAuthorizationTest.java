package com.cns.spring_house.sale.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.cns.spring_house.sale.dto.RefundRequestDTO;
import com.cns.spring_house.sale.dto.ReverseSaleRequestDTO;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.service.SaleDashboardService;
import com.cns.spring_house.sale.service.SaleService;

/**
 * QUIEN puede revertir una venta.
 *
 * Revertir dejo de ser una correccion de inventario cuando pudo llevar una
 * devolucion: quien revierte puede sacar dinero del cajon. La operacion es del
 * VENDEDOR, que es quien atiende la caja —su modulo tiene la pantalla `Caja`,
 * completa las ventas y cobra las cambiarias—. La Secretaria cierra el dia,
 * pero no lo opera.
 *
 * La prueba levanta un contexto con `@EnableMethodSecurity` a proposito: con el
 * `standaloneSetup` de MockMvc el controlador se monta SIN el proxy de
 * seguridad, y una prueba de ruta seguiria pasando aunque alguien borrara el
 * `@PreAuthorize`. Aqui la anotacion esta de verdad en juego.
 */
@TestInstance(Lifecycle.PER_CLASS)
class SaleReversalAuthorizationTest {

    /** El unico rol autorizado a revertir y devolver. */
    private static final String CASHIER_ROLE = "SELLER";

    /** Los cinco roles restantes. Ninguno puede revertir, ADMIN incluido. */
    private static final List<String> NON_CASHIER_ROLES =
            List.of("SECRETARY", "ADMIN", "CONTROL", "POLICE", "OPERATOR");

    private AnnotationConfigApplicationContext context;
    private SaleController secured;
    private SaleService securedService;

    @BeforeEach
    void startSecuredContext() {
        context = new AnnotationConfigApplicationContext(SecuredConfig.class);
        secured = context.getBean(SaleController.class);
        securedService = context.getBean(SaleService.class);
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

    private static ReverseSaleRequestDTO withRefund() {
        return new ReverseSaleRequestDTO(
                "Devolucion al cliente",
                new RefundRequestDTO(new BigDecimal("1000.00"), PaymentMethod.CASH));
    }

    @Test
    void theCashierCanReverseWithAndWithoutRefund() {
        authenticateAs(CASHIER_ROLE);

        assertThatCode(() -> {
            secured.reverse(UUID.randomUUID(), new ReverseSaleRequestDTO("Sin devolucion"));
            secured.reverse(UUID.randomUUID(), withRefund());
        }).doesNotThrowAnyException();
    }

    /**
     * Y NINGUN otro rol, ADMIN incluido.
     *
     * No se admite excepcion administrativa: un permiso de administracion no es
     * la responsabilidad sobre el dinero del dia, y anadirlo dejaria a dos
     * personas capaces de mover la caja sin que ninguna responda por ella.
     */
    @Test
    void everyOtherAuthenticatedRoleIsDenied() {
        for (String role : NON_CASHIER_ROLES) {
            authenticateAs(role);

            assertThatThrownBy(() -> secured.reverse(
                    UUID.randomUUID(), new ReverseSaleRequestDTO("Sin devolucion")))
                    .as("reversion con rol %s", role)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    /**
     * Un rol sin permiso que ADEMAS manda una devolucion es rechazado en la
     * puerta: el servicio no se llega a invocar, asi que no hay reversion, ni
     * stock restaurado, ni devolucion insertada.
     */
    @Test
    void anUnauthorizedRefundNeverReachesTheDomain() {
        for (String role : NON_CASHIER_ROLES) {
            authenticateAs(role);

            assertThatThrownBy(() -> secured.reverse(UUID.randomUUID(), withRefund()))
                    .as("devolucion con rol %s", role)
                    .isInstanceOf(AccessDeniedException.class);
        }

        verify(securedService, never()).reverse(any(), any());
    }

    @Test
    void anonymousIsDenied() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> secured.reverse(
                UUID.randomUUID(), new ReverseSaleRequestDTO("Sin devolucion")))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verify(securedService, never()).reverse(any(), any());
    }

    /**
     * El cerrojo es de la REVERSION, no del controlador entero.
     *
     * Se comprueba con un rol que NO puede revertir: si la restriccion se
     * hubiera puesto sobre la clase, este `cancel` tambien fallaria. Usar aqui
     * el propio SELLER no probaria nada, porque a el se le permite todo.
     */
    @Test
    void theRestrictionCoversReversalOnlyAndNotTheWholeController() {
        authenticateAs("SECRETARY");

        assertThatCode(() -> secured.cancel(
                UUID.randomUUID(),
                new com.cns.spring_house.sale.dto.CancelSaleRequestDTO("Cliente desistio")))
                .doesNotThrowAnyException();
    }

    @Configuration
    @EnableMethodSecurity
    static class SecuredConfig {

        @Bean
        SaleService saleService() {
            return Mockito.mock(SaleService.class);
        }

        @Bean
        SaleDashboardService saleDashboardService() {
            return Mockito.mock(SaleDashboardService.class);
        }

        @Bean
        SaleController saleController(
                SaleService service, SaleDashboardService dashboardService) {
            return new SaleController(service, dashboardService);
        }
    }
}
