package com.cns.spring_house.vehiclemovement.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionRequestDTO;
import com.cns.spring_house.vehiclemovement.dto.PhoneSuggestionResponseDTO;
import com.cns.spring_house.vehiclemovement.service.PoliceDashboardService;
import com.cns.spring_house.vehiclemovement.service.VehicleMovementService;

/**
 * Contrato HTTP de la sugerencia de telefono: que ruta responde, que llega al
 * servicio, que codigo devuelve cada caso y QUIEN puede llamarla.
 */
@ExtendWith(MockitoExtension.class)
class VehicleMovementPhoneSuggestionControllerTest {

    private static final String URL = "/v1/vehicle-movements/phone-suggestion";

    @Mock
    private VehicleMovementService service;

    @Mock
    private PoliceDashboardService dashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new VehicleMovementController(service, dashboardService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("devuelve el telefono sugerido de la placa")
    void returnsTheSuggestedPhone() throws Exception {
        when(service.getPhoneSuggestion(any()))
                .thenReturn(new PhoneSuggestionResponseDTO("58452136"));

        mockMvc.perform(get(URL).param("plate", "P123ABC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("58452136"));
    }

    /**
     * Una placa sin telefono previo NO es un 404. "Esta placa no tiene telefono
     * registrado" es una respuesta valida a una pregunta valida; tratarla como
     * error obligaria al cliente a distinguir un fallo real de una ausencia
     * normal, y el alta se bloquearia por algo que no es un problema.
     */
    @Test
    @DisplayName("una placa sin telefono previo responde 200 con phone null")
    void anunknownPlateAnswersOkWithNullPhone() throws Exception {
        when(service.getPhoneSuggestion(any()))
                .thenReturn(new PhoneSuggestionResponseDTO(null));

        mockMvc.perform(get(URL).param("plate", "P999XYZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    @DisplayName("la placa llega al servicio ya normalizada")
    void theplateReachesTheServiceNormalized() throws Exception {
        when(service.getPhoneSuggestion(any()))
                .thenReturn(new PhoneSuggestionResponseDTO(null));

        mockMvc.perform(get(URL).param("plate", "  p123abc  "))
                .andExpect(status().isOk());

        ArgumentCaptor<PhoneSuggestionRequestDTO> captor =
                ArgumentCaptor.forClass(PhoneSuggestionRequestDTO.class);
        verify(service).getPhoneSuggestion(captor.capture());
        org.assertj.core.api.Assertions
                .assertThat(captor.getValue().plate())
                .isEqualTo("P123ABC");
    }

    /**
     * Una placa incompleta es 400 y no una busqueda: sin esto el endpoint
     * aceptaria prefijos y serviria para tantear telefonos placa a placa.
     */
    @Test
    @DisplayName("una placa incompleta es un 400 de validacion, no un 500")
    void anincompletePlateIsABadRequest() throws Exception {
        mockMvc.perform(get(URL).param("plate", "P"))
                .andExpect(status().isBadRequest());

        verify(service, never()).getPhoneSuggestion(any());
    }

    @Test
    @DisplayName("sin placa responde 400")
    void amissingPlateIsABadRequest() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isBadRequest());

        verify(service, never()).getPhoneSuggestion(any());
    }

    /** El taller NO viaja desde el cliente y el servicio no lo recibe. */
    @Test
    @DisplayName("un taller enviado por el cliente no llega al servicio")
    void aclientWorkshopParameterIsIgnored() throws Exception {
        when(service.getPhoneSuggestion(any()))
                .thenReturn(new PhoneSuggestionResponseDTO(null));

        mockMvc.perform(get(URL)
                        .param("plate", "P123ABC")
                        .param("workshop", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk());

        ArgumentCaptor<PhoneSuggestionRequestDTO> captor =
                ArgumentCaptor.forClass(PhoneSuggestionRequestDTO.class);
        verify(service).getPhoneSuggestion(captor.capture());
        // El record solo tiene la placa: no hay donde meter un taller.
        org.assertj.core.api.Assertions
                .assertThat(PhoneSuggestionRequestDTO.class.getRecordComponents())
                .hasSize(1);
    }

    // ── Autorizacion ─────────────────────────────────────────────

    /**
     * La sugerencia es de POLICIA y solo de POLICIA.
     *
     * `SecurityConfig` unicamente exige estar autenticado, asi que sin el
     * `@PreAuthorize` cualquier rol con sesion valida podria consultar telefonos
     * placa por placa. Admin ve el telefono en el HISTORIAL, que es otra cosa: no
     * abre bitacoras y no necesita la sugerencia.
     *
     * El `standaloneSetup` de arriba monta el controlador SIN el proxy de
     * seguridad, asi que aquella prueba pasaria igual aunque alguien borrara la
     * anotacion. Este bloque levanta un contexto con `@EnableMethodSecurity` —la
     * misma que ya activa {@code SecurityConfig}— para que este realmente en
     * juego.
     */
    @Nested
    class Authorization {

        private AnnotationConfigApplicationContext context;
        private VehicleMovementController secured;
        private VehicleMovementService securedService;

        @BeforeEach
        void startSecuredContext() {
            context = new AnnotationConfigApplicationContext(SecuredConfig.class);
            secured = context.getBean(VehicleMovementController.class);
            securedService = context.getBean(VehicleMovementService.class);
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
        @DisplayName("POLICE alcanza la sugerencia")
        void policeReachesTheSuggestion() {
            authenticateAs("POLICE");

            assertThatCode(() -> secured.getPhoneSuggestion(
                    new PhoneSuggestionRequestDTO("P123ABC")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ningun otro rol autenticado alcanza la sugerencia")
        void everyOtherAuthenticatedRoleIsDenied() {
            for (String role : List.of("ADMIN", "SELLER", "SECRETARY", "CONTROL", "OPERATOR")) {
                authenticateAs(role);

                assertThatThrownBy(() -> secured.getPhoneSuggestion(
                        new PhoneSuggestionRequestDTO("P123ABC")))
                        .as("sugerencia con rol %s", role)
                        .isInstanceOf(AccessDeniedException.class);
            }

            // Denegado en la puerta: el servicio nunca llega a consultar nada.
            verify(securedService, never()).getPhoneSuggestion(any());
        }

        @Test
        @DisplayName("sin autenticacion la sugerencia se rechaza")
        void anonymousIsDenied() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> secured.getPhoneSuggestion(
                    new PhoneSuggestionRequestDTO("P123ABC")))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verify(securedService, never()).getPhoneSuggestion(any());
        }

        /**
         * El resto del controlador se deja como estaba. Anotar endpoints ajenos
         * es un cambio de alcance distinto, y ademas romperia usos legitimos: el
         * historial lo consulta tambien Vendedor para sugerir placas al facturar.
         */
        @Test
        @DisplayName("el historial sigue abierto a los demas roles autenticados")
        void thehistoryRemainsOpenToOtherRoles() {
            authenticateAs("SELLER");

            assertThatCode(() -> secured.getAll(
                    new com.cns.spring_house.vehiclemovement.dto
                            .SearchVehicleMovementRequestDTO(null, null, null, null)))
                    .doesNotThrowAnyException();
        }
    }

    /** Contexto minimo con seguridad de metodo activa, como en produccion. */
    @Configuration
    @EnableMethodSecurity
    static class SecuredConfig {

        @Bean
        VehicleMovementService vehicleMovementService() {
            return Mockito.mock(VehicleMovementService.class);
        }

        @Bean
        PoliceDashboardService policeDashboardService() {
            return Mockito.mock(PoliceDashboardService.class);
        }

        @Bean
        VehicleMovementController vehicleMovementController(
                VehicleMovementService service, PoliceDashboardService dashboardService) {
            return new VehicleMovementController(service, dashboardService);
        }
    }
}
