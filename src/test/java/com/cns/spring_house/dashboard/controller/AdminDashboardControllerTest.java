package com.cns.spring_house.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.acquisition.entity.AcquisitionType;
import com.cns.spring_house.dashboard.dto.AdminDashboardOverviewResponseDTO;
import com.cns.spring_house.dashboard.dto.CustomerPerformanceDTO;
import com.cns.spring_house.dashboard.dto.EmployeePerformanceDTO;
import com.cns.spring_house.dashboard.dto.ProductPerformanceDTO;
import com.cns.spring_house.dashboard.dto.ProductSalesDTO;
import com.cns.spring_house.dashboard.dto.SalesGranularity;
import com.cns.spring_house.dashboard.dto.SalesTrendPointDTO;
import com.cns.spring_house.dashboard.dto.SalesTrendResponseDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyPointDTO;
import com.cns.spring_house.dashboard.dto.VehicleOccupancyResponseDTO;
import com.cns.spring_house.dashboard.service.AdminDashboardService;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * El contrato HTTP del panel administrativo: que ruta responde, que llega al
 * servicio y quien puede llamarlo.
 *
 * La autorizacion se comprueba aparte, en {@link Authorization}: el
 * `standaloneSetup` de MockMvc monta el controlador SIN el proxy de seguridad,
 * de modo que una prueba de ruta pasaria igual aunque alguien borrara el
 * `@PreAuthorize`. Ese bloque levanta un contexto con
 * `@EnableMethodSecurity` —la misma anotacion que ya activa
 * {@code SecurityConfig}— para que la anotacion este realmente en juego.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardControllerTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 15);

    @Mock
    private AdminDashboardService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminDashboardController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── Rutas y contrato ─────────────────────────────────────────

    @Test
    void overviewExposesKpisAndRankings() throws Exception {
        when(service.getOverview()).thenReturn(overview());

        mockMvc.perform(get("/v1/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-08-15"))
                .andExpect(jsonPath("$.totalGeneratedSales").value(1250.00))
                .andExpect(jsonPath("$.currentVehicles").value(3))
                .andExpect(jsonPath("$.workerCount").value(9))
                .andExpect(jsonPath("$.topSellingProducts[0].productName").value("TORNILLO"))
                .andExpect(jsonPath("$.topSellingProducts[0].generatedAmount").value(400.00))
                .andExpect(jsonPath("$.leastSellingProducts[0].quantity").value(0))
                .andExpect(jsonPath("$.topServiceEmployees[0].employeeName").value("ANA LOPEZ"))
                .andExpect(jsonPath("$.topTransformationEmployees[0].employeeName")
                        .value("BETO RUIZ"))
                .andExpect(jsonPath("$.topCustomers[0].nit").value("12345678"));
    }

    /**
     * El contrato NO expone costo ni margen.
     *
     * La cifra del ranking es el monto que la venta genero; restarle un costo
     * responderia una pregunta de rentabilidad que este panel no hace, y con el
     * modelo actual ni siquiera podria hacerse bien. Se comprueba que los campos
     * retirados no reaparecen: dejar uno vivo bastaria para que la interfaz
     * volviera a rotular ingresos como ganancia.
     */
    @Test
    void overviewExposesNoCostOrProfitFields() throws Exception {
        when(service.getOverview()).thenReturn(overview());

        mockMvc.perform(get("/v1/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profitBasis").doesNotExist())
                .andExpect(jsonPath("$.topSellingProducts[0].estimatedProfit").doesNotExist())
                .andExpect(jsonPath("$.topSellingProducts[0].unitCost").doesNotExist())
                .andExpect(jsonPath("$.topSellingProducts[0].generatedAmount").value(400.00))
                .andExpect(jsonPath("$.topCustomers[0].generatedAmount").value(500.00));
    }

    @Test
    void salesTrendDefaultsToDailyGranularityWithoutPeriod() throws Exception {
        when(service.getSalesTrend(any(), any())).thenReturn(trend());

        mockMvc.perform(get("/v1/dashboard/sales-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAY"))
                .andExpect(jsonPath("$.points[0].generatedAmount").value(100.00));

        verify(service).getSalesTrend(SalesGranularity.DAY, null);
    }

    @Test
    void salesTrendForwardsGranularityAndPeriod() throws Exception {
        when(service.getSalesTrend(any(), any())).thenReturn(trend());

        mockMvc.perform(get("/v1/dashboard/sales-trend")
                        .param("granularity", "MONTH")
                        .param("period", "2025"))
                .andExpect(status().isOk());

        verify(service).getSalesTrend(SalesGranularity.MONTH, "2025");
    }

    /** Una granularidad inexistente no llega al servicio. */
    @Test
    void salesTrendRejectsAnUnknownGranularity() throws Exception {
        mockMvc.perform(get("/v1/dashboard/sales-trend")
                        .param("granularity", "WEEK"))
                .andExpect(status().isBadRequest());

        verify(service, never()).getSalesTrend(any(), any());
    }

    @Test
    void vehicleOccupancyDefaultsToThirtyDays() throws Exception {
        when(service.getVehicleOccupancy(Mockito.anyInt())).thenReturn(occupancy());

        mockMvc.perform(get("/v1/dashboard/vehicle-occupancy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVehicles").value(3))
                .andExpect(jsonPath("$.peakVehicles").value(5));

        verify(service).getVehicleOccupancy(30);
    }

    /** El rango invalido responde 400 con el mensaje de negocio, no una serie recortada. */
    @Test
    void vehicleOccupancyPropagatesTheBusinessValidation() throws Exception {
        when(service.getVehicleOccupancy(Mockito.anyInt()))
                .thenThrow(new ValidationException("El periodo debe estar entre 1 y 366 dias"));

        mockMvc.perform(get("/v1/dashboard/vehicle-occupancy").param("days", "999"))
                .andExpect(status().isBadRequest());
    }

    /**
     * NINGUNA ruta admite un taller. Es la garantia de aislamiento: si un
     * parametro llegara a existir, un administrador podria pedir las cifras de
     * otro taller escribiendolo en la URL. Enviarlo hoy no hace nada, y esta
     * prueba falla el dia que alguien lo enganche.
     */
    @Test
    void noEndpointAcceptsAWorkshopFromTheClient() throws Exception {
        when(service.getOverview()).thenReturn(overview());
        when(service.getSalesTrend(any(), any())).thenReturn(trend());
        when(service.getVehicleOccupancy(Mockito.anyInt())).thenReturn(occupancy());
        String foreignWorkshop = UUID.randomUUID().toString();

        mockMvc.perform(get("/v1/dashboard/overview").param("workshopId", foreignWorkshop))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/dashboard/sales-trend").param("workshop", foreignWorkshop))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/dashboard/vehicle-occupancy").param("workshopId", foreignWorkshop))
                .andExpect(status().isOk());

        // El servicio recibe exactamente lo mismo que sin el parametro.
        verify(service).getOverview();
        verify(service).getSalesTrend(SalesGranularity.DAY, null);
        verify(service).getVehicleOccupancy(30);
    }

    // ── Autorizacion ─────────────────────────────────────────────

    /**
     * El panel es de ADMIN y solo de ADMIN.
     *
     * La cadena de filtros de {@code SecurityConfig} unicamente exige estar
     * autenticado, asi que sin `@PreAuthorize` un vendedor con sesion valida
     * leeria la rentabilidad, los clientes y la plantilla del taller entero.
     * Aqui se comprueba con el proxy de seguridad puesto, que es lo unico que
     * demuestra que la anotacion hace algo.
     *
     * El prefijo `ROLE_` es el que pone {@code JwtConfig} al convertir la claim
     * `role` del token.
     */
    @Nested
    class Authorization {

        private AnnotationConfigApplicationContext context;
        private AdminDashboardController secured;
        private AdminDashboardService securedService;

        @BeforeEach
        void startSecuredContext() {
            context = new AnnotationConfigApplicationContext(SecuredConfig.class);
            secured = context.getBean(AdminDashboardController.class);
            securedService = context.getBean(AdminDashboardService.class);
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
        void adminReachesEveryEndpoint() {
            authenticateAs("ADMIN");

            assertThatCode(() -> {
                secured.getOverview();
                secured.getSalesTrend(SalesGranularity.DAY, null);
                secured.getVehicleOccupancy(30);
                // Las analiticas financieras nuevas viven bajo el MISMO
                // `@PreAuthorize` de clase, asi que se comprueban aqui: una
                // ruta nueva que se saltara la anotacion pasaria inadvertida.
                secured.getAcquisitionTrend(SalesGranularity.DAY, null, null);
                secured.getAcquisitionTrend(
                        SalesGranularity.MONTH, null, AcquisitionType.IMPORTATION);
                secured.getSalesProfitability(SalesGranularity.DAY, null);
                secured.getTopSuppliers(SalesGranularity.DAY, null);
            }).doesNotThrowAnyException();
        }

        @Test
        void otherAuthenticatedRolesAreDenied() {
            for (String role : List.of("SELLER", "SECRETARY", "POLICE", "CONTROL", "OPERATOR")) {
                authenticateAs(role);

                assertThatThrownBy(() -> secured.getOverview())
                        .as("overview con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> secured.getSalesTrend(SalesGranularity.DAY, null))
                        .as("revenue-trend con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> secured.getVehicleOccupancy(30))
                        .as("vehicle-occupancy con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() ->
                        secured.getAcquisitionTrend(SalesGranularity.DAY, null, null))
                        .as("acquisition-trend con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() ->
                        secured.getSalesProfitability(SalesGranularity.DAY, null))
                        .as("sales-profitability con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() ->
                        secured.getTopSuppliers(SalesGranularity.DAY, null))
                        .as("top-suppliers con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
            }

            // Denegado en la puerta: el servicio nunca llega a consultar nada.
            verify(securedService, never()).getOverview();
            verify(securedService, never()).getSalesTrend(any(), any());
            verify(securedService, never()).getVehicleOccupancy(Mockito.anyInt());
            verify(securedService, never()).getAcquisitionTrend(any(), any(), any());
            verify(securedService, never()).getSalesCostProfitTrend(any(), any());
            verify(securedService, never()).getTopSuppliers(any(), any());
        }

        @Test
        void anonymousIsDenied() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> secured.getOverview())
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verify(securedService, never()).getOverview();
        }
    }

    /** Contexto minimo con seguridad de metodo activa, como en produccion. */
    @Configuration
    @EnableMethodSecurity
    static class SecuredConfig {

        @Bean
        AdminDashboardService adminDashboardService() {
            return Mockito.mock(AdminDashboardService.class);
        }

        @Bean
        AdminDashboardController adminDashboardController(AdminDashboardService service) {
            return new AdminDashboardController(service);
        }
    }

    // ── Datos ────────────────────────────────────────────────────

    private static AdminDashboardOverviewResponseDTO overview() {
        return new AdminDashboardOverviewResponseDTO(
                BUSINESS_DATE,
                LocalDate.of(2025, 2, 3),
                new BigDecimal("1250.00"),
                7L,
                3L,
                9L,
                List.of(
                        new ProductPerformanceDTO(
                                UUID.randomUUID(), "TORNILLO", 10L,
                                new BigDecimal("400.00")),
                        new ProductPerformanceDTO(
                                UUID.randomUUID(), "SERVICIO", 1L,
                                new BigDecimal("100.00"))),
                List.of(new ProductSalesDTO(UUID.randomUUID(), "PARADO", 0L)),
                List.of(new EmployeePerformanceDTO(UUID.randomUUID(), "ANA LOPEZ", 3L)),
                List.of(new EmployeePerformanceDTO(UUID.randomUUID(), "BETO RUIZ", 2L)),
                List.of(new CustomerPerformanceDTO(
                        UUID.randomUUID(), "CLIENTE", "12345678", 2L,
                        new BigDecimal("500.00"))));
    }

    private static SalesTrendResponseDTO trend() {
        return new SalesTrendResponseDTO(
                SalesGranularity.DAY,
                LocalDate.of(2026, 8, 1),
                BUSINESS_DATE,
                new BigDecimal("100.00"),
                1L,
                List.of(new SalesTrendPointDTO(
                        LocalDate.of(2026, 8, 1), 1L, new BigDecimal("100.00"))));
    }

    private static VehicleOccupancyResponseDTO occupancy() {
        return new VehicleOccupancyResponseDTO(
                LocalDate.of(2026, 8, 13),
                BUSINESS_DATE,
                3L,
                5L,
                List.of(new VehicleOccupancyPointDTO(BUSINESS_DATE, 3L)));
    }

}
