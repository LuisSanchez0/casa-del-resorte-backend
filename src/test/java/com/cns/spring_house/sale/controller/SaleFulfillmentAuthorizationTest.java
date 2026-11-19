package com.cns.spring_house.sale.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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

import com.cns.spring_house.sale.dto.BlockFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.DispatchSaleRequestDTO;
import com.cns.spring_house.sale.dto.FulfillTransformationRequestDTO;
import com.cns.spring_house.sale.dto.ReleaseFulfillmentRequestDTO;
import com.cns.spring_house.sale.dto.RequestSpecificationDTO;
import com.cns.spring_house.sale.dto.SaleLineGuidanceRequestDTO;
import com.cns.spring_house.sale.dto.UpdateRequestPriceDTO;
import com.cns.spring_house.sale.service.SaleDashboardService;
import com.cns.spring_house.sale.service.SaleLineGuidanceService;
import com.cns.spring_house.sale.service.SaleFulfillmentService;
import com.cns.spring_house.sale.service.SaleService;

/**
 * QUIEN puede mover una solicitud de la comanda.
 *
 * El vendedor pide y envia; el Operador entrega lo que ya existe ("Pedidos
 * por liberar", que fue de Control), fabrica y ejecuta. Nadie puede hacer el
 * trabajo de otro: un vendedor no puede dar por entregada ni por fabricada una
 * solicitud, y Control ya no puede ni ver ni liberar ninguna. ADMIN puede lo
 * del Operador como supervision, pero no enviar la comanda de un vendedor.
 *
 * Como SaleReversalAuthorizationTest, se levanta un contexto con
 * `@EnableMethodSecurity`: la anotacion esta de verdad en juego.
 */
@TestInstance(Lifecycle.PER_CLASS)
class SaleFulfillmentAuthorizationTest {

    private static final List<String> ALL_ROLES =
            List.of("SELLER", "SECRETARY", "ADMIN", "CONTROL", "POLICE", "OPERATOR");

    private AnnotationConfigApplicationContext context;
    private SaleFulfillmentController fulfillment;
    private SaleController sales;
    private SaleFulfillmentService fulfillmentService;
    private SaleService saleService;

    @BeforeEach
    void startSecuredContext() {
        context = new AnnotationConfigApplicationContext(SecuredConfig.class);
        fulfillment = context.getBean(SaleFulfillmentController.class);
        sales = context.getBean(SaleController.class);
        fulfillmentService = context.getBean(SaleFulfillmentService.class);
        saleService = context.getBean(SaleService.class);
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

    /** Cada accion y los roles que la pueden ejecutar. */
    private Map<String, Action> actions() {
        UUID id = UUID.randomUUID();
        return Map.ofEntries(
                Map.entry("pedidos por liberar", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.getReleaseQueue(null, null, null))),
                Map.entry("unidades elegibles", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.getEligibleItems(id))),
                Map.entry("entregar", new Action(
                        Set.of("OPERATOR", "ADMIN"),
                        c -> c.fulfillment.release(id, new ReleaseFulfillmentRequestDTO(null)))),
                Map.entry("cola del Operador", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.getOperatorQueue(null, null, null))),
                Map.entry("tomar", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.start(id))),
                Map.entry("bloquear", new Action(
                        Set.of("OPERATOR", "ADMIN"),
                        c -> c.fulfillment.block(id, new BlockFulfillmentRequestDTO("Sin material")))),
                Map.entry("piezas de origen", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.getSources(id, null))),
                Map.entry("fabricar", new Action(
                        Set.of("OPERATOR", "ADMIN"),
                        c -> c.fulfillment.transform(id,
                                new FulfillTransformationRequestDTO(UUID.randomUUID(), null)))),
                Map.entry("terminar servicio", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.completeService(id))),
                Map.entry("ver solicitud", new Action(
                        Set.of("OPERATOR", "ADMIN"), c -> c.fulfillment.get(id))),
                Map.entry("enviar comanda", new Action(
                        Set.of("SELLER"),
                        c -> c.sales.dispatch(id, new DispatchSaleRequestDTO(null)))),
                Map.entry("solicitar especificacion", new Action(
                        Set.of("SELLER"),
                        c -> c.sales.requestSpecification(id, new RequestSpecificationDTO(
                                UUID.randomUUID(), null, Map.of("largo", 1), 1,
                                BigDecimal.ONE, null, null)))),
                Map.entry("ventas listas", new Action(
                        Set.of("SELLER"), c -> c.sales.getReady(null))),
                // La guia de precio de la caja: precios de catalogo, ultimo
                // precio cobrado y comparables del taller. Solo quien vende.
                Map.entry("guia de precio", new Action(
                        Set.of("SELLER"),
                        c -> c.sales.lineGuidance(new SaleLineGuidanceRequestDTO(
                                UUID.randomUUID(), null, null, null)))),
                // El precio lo decide el vendedor (o Administracion), nunca quien
                // entrega o fabrica.
                Map.entry("revisar precio", new Action(
                        Set.of("SELLER", "ADMIN"),
                        c -> c.sales.updateRequestPrice(id, new UpdateRequestPriceDTO(BigDecimal.TEN, BigDecimal.TEN)))));
    }

    private record Action(Set<String> allowed, Consumer<SaleFulfillmentAuthorizationTest> call) {
    }

    @Test
    void everyActionIsOpenOnlyToItsRoles() {
        actions().forEach((name, action) -> {
            for (String role : ALL_ROLES) {
                authenticateAs(role);
                if (action.allowed().contains(role)) {
                    assertThatCode(() -> action.call().accept(this))
                            .as("%s con rol %s", name, role)
                            .doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> action.call().accept(this))
                            .as("%s con rol %s", name, role)
                            .isInstanceOf(AccessDeniedException.class);
                }
            }
        });
    }

    /** Un vendedor no llega ni a invocar la entrega ni la fabricacion. */
    @Test
    void aSellerCannotFakeWorkshopWork() {
        authenticateAs("SELLER");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> fulfillment.release(id, new ReleaseFulfillmentRequestDTO(null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fulfillment.transform(
                id, new FulfillTransformationRequestDTO(UUID.randomUUID(), null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fulfillment.completeService(id))
                .isInstanceOf(AccessDeniedException.class);

        verify(fulfillmentService, never()).release(any(), any());
        verify(fulfillmentService, never()).transform(any(), any());
        verify(fulfillmentService, never()).completeService(any());
    }

    /**
     * "Pedidos por liberar" paso al Operador: una cuenta solo de Control que
     * llame directo a la API no llega al servicio en NINGUNA operacion de la
     * comanda —ni listar, ni ver, ni elegir unidad, ni liberar—.
     */
    @Test
    void controlNoLongerReachesAnyFulfillmentOperation() {
        authenticateAs("CONTROL");
        UUID id = UUID.randomUUID();
        List<Runnable> calls = List.of(
                () -> fulfillment.getReleaseQueue(null, null, null),
                () -> fulfillment.getOperatorQueue(null, null, null),
                () -> fulfillment.get(id),
                () -> fulfillment.getEligibleItems(id),
                () -> fulfillment.release(id, new ReleaseFulfillmentRequestDTO(null)),
                () -> fulfillment.release(id, null),
                () -> fulfillment.start(id),
                () -> fulfillment.block(id, new BlockFulfillmentRequestDTO("x")),
                () -> fulfillment.getSources(id, null),
                () -> fulfillment.transform(id, new FulfillTransformationRequestDTO(UUID.randomUUID(), null)),
                () -> fulfillment.completeService(id));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
        }
        Mockito.verifyNoInteractions(fulfillmentService);
    }

    /** Y el Operador, que ahora libera, llega al servicio con lo que pidio. */
    @Test
    void theOperatorReachesTheReleaseOperations() {
        authenticateAs("OPERATOR");
        UUID id = UUID.randomUUID();
        UUID unit = UUID.randomUUID();

        fulfillment.getReleaseQueue(List.of(), 5, 1);
        fulfillment.getEligibleItems(id);
        fulfillment.release(id, new ReleaseFulfillmentRequestDTO(unit));
        fulfillment.release(id, null);

        verify(fulfillmentService).getQueue(
                com.cns.spring_house.sale.entity.FulfillmentArea.CONTROL, List.of(), 5, 1);
        verify(fulfillmentService).getEligibleItems(id);
        verify(fulfillmentService).release(id, new ReleaseFulfillmentRequestDTO(unit));
        // Sin cuerpo, la unidad reservada: el mismo contrato de siempre.
        verify(fulfillmentService).release(id, new ReleaseFulfillmentRequestDTO(null));
    }

    @Test
    void anonymousIsDenied() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> fulfillment.getReleaseQueue(null, null, null))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> sales.dispatch(UUID.randomUUID(), new DispatchSaleRequestDTO(null)))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verify(saleService, never()).dispatch(any(), any());
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
        SaleFulfillmentService saleFulfillmentService() {
            return Mockito.mock(SaleFulfillmentService.class);
        }

        @Bean
        SaleLineGuidanceService saleLineGuidanceService() {
            return Mockito.mock(SaleLineGuidanceService.class);
        }

        @Bean
        SaleController saleController(
                SaleService service,
                SaleDashboardService dashboardService,
                SaleLineGuidanceService guidanceService) {
            return new SaleController(service, dashboardService, guidanceService);
        }

        @Bean
        SaleFulfillmentController saleFulfillmentController(SaleFulfillmentService service) {
            return new SaleFulfillmentController(service);
        }
    }
}
