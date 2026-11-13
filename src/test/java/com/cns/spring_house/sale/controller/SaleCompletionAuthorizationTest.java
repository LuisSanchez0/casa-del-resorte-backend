package com.cns.spring_house.sale.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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

import com.cns.spring_house.sale.dto.CompleteSaleRequestDTO;
import com.cns.spring_house.sale.entity.InvoiceType;
import com.cns.spring_house.sale.entity.PaymentMethod;
import com.cns.spring_house.sale.entity.TaxMode;
import com.cns.spring_house.sale.service.SaleDashboardService;
import com.cns.spring_house.sale.service.SaleService;

/**
 * QUIEN puede emitir una venta y, con ello, fijar su NIT o CF definitivo.
 *
 * La identidad fiscal se elige o corrige en la misma peticion que emite: no
 * hay otra puerta para cambiarla antes de emitir. Por eso esta puerta es la
 * del VENDEDOR, igual que revertir: emitir una factura normal cobra y decidir
 * a quien se factura es de quien atiende la caja. Ningun otro rol del taller
 * —ADMIN incluido— puede emitir ni reescribir el NIT de una venta.
 *
 * Contexto con `@EnableMethodSecurity` real, por la misma razon que
 * {@link SaleReversalAuthorizationTest}: sin el proxy, una prueba de ruta
 * seguiria pasando aunque alguien borrara el `@PreAuthorize`.
 */
@TestInstance(Lifecycle.PER_CLASS)
class SaleCompletionAuthorizationTest {

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

    private static CompleteSaleRequestDTO withNit() {
        return new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH, TaxMode.NIT, UUID.randomUUID(), null);
    }

    private static CompleteSaleRequestDTO asConsumidorFinal() {
        return new CompleteSaleRequestDTO(
                InvoiceType.INVOICE, PaymentMethod.CASH, TaxMode.CF, null, null);
    }

    @Test
    void theCashierCanIssueWithNitOrAsConsumidorFinal() {
        authenticateAs("SELLER");

        assertThatCode(() -> {
            secured.complete(UUID.randomUUID(), withNit());
            secured.complete(UUID.randomUUID(), asConsumidorFinal());
        }).doesNotThrowAnyException();
    }

    /** Ni con NIT ni a CF: ningun otro rol llega al servicio que emite. */
    @Test
    void everyOtherRoleIsDeniedAndNeverReachesTheService() {
        for (String role : NON_CASHIER_ROLES) {
            authenticateAs(role);

            assertThatThrownBy(() -> secured.complete(UUID.randomUUID(), withNit()))
                    .as("emitir con NIT con rol %s", role)
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> secured.complete(UUID.randomUUID(), asConsumidorFinal()))
                    .as("emitir a CF con rol %s", role)
                    .isInstanceOf(AccessDeniedException.class);
        }

        verify(securedService, never()).completeSale(any(), any());
    }

    @Test
    void anonymousIsDenied() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> secured.complete(UUID.randomUUID(), withNit()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verify(securedService, never()).completeSale(any(), any());
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
