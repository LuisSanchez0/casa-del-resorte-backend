package com.cns.spring_house.customer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;
import com.cns.spring_house.customer.service.CustomerService;
import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.exception.EntityAlreadyExistsException;

/**
 * Contrato HTTP real del alta y la actualizacion de clientes.
 *
 * Estas pruebas existen por dos defectos concretos que llegaron a produccion del
 * entorno local: el alta colgaba de `/{id}` (un identificador que no existe
 * todavia y que ademas no se usaba) y ninguno de los dos metodos declaraba
 * `@RequestBody`, asi que Spring enlazaba el DTO desde parametros de consulta.
 * El resultado era un 405 en la ruta canonica y un PATCH que respondia 200 sin
 * cambiar nada.
 */
@ExtendWith(MockitoExtension.class)
class CustomerControllerTest {

    @Mock
    private CustomerService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CustomerController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String text(int length) {
        return "A".repeat(length);
    }

    @Nested
    @DisplayName("POST /v1/customers")
    class Create {

        @Test
        void createsFromAJsonBodyOnTheCollectionRoute() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.create(any())).thenReturn(
                    new CustomerResponseDTO(id, "334821282", "JUAN PEREZ"));

            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\"Juan Perez\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.nit").value("334821282"))
                    .andExpect(jsonPath("$.name").value("JUAN PEREZ"));
        }

        @Test
        void theServiceReceivesTheBodyAndNotAnEmptyDto() throws Exception {
            when(service.create(any())).thenReturn(
                    new CustomerResponseDTO(UUID.randomUUID(), "334821282", "JUAN PEREZ"));

            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\"Juan Perez\"}"))
                    .andExpect(status().isCreated());

            ArgumentCaptor<CreateCustomerRequestDTO> captor =
                    ArgumentCaptor.forClass(CreateCustomerRequestDTO.class);
            verify(service).create(captor.capture());

            // Es justo lo que fallaba: sin @RequestBody ambos campos llegaban null.
            assertThat(captor.getValue().nit()).isEqualTo("334821282");
            assertThat(captor.getValue().name()).isEqualTo("Juan Perez");
        }

        /** La ruta antigua desaparece: un cliente sin crear no tiene identificador. */
        @Test
        void theRouteWithAnIdNoLongerCreates() throws Exception {
            mockMvc.perform(post("/v1/customers/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\"Juan Perez\"}"))
                    .andExpect(status().isMethodNotAllowed());

            verify(service, never()).create(any());
        }

        @Test
        void rejectsAMissingNit() throws Exception {
            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Juan Perez\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        @Test
        void rejectsAMissingName() throws Exception {
            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        @Test
        void rejectsANitThatIsNotEightToThirteenDigits() throws Exception {
            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"1234567\",\"name\":\"Juan Perez\"}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"CF\",\"name\":\"Juan Perez\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        @Test
        void rejectsABlankName() throws Exception {
            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        @Test
        void rejectsANameLongerThanTheColumn() throws Exception {
            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\""
                            + text(ColumnLimits.CUSTOMER_NAME + 1) + "\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        @Test
        void acceptsANameExactlyAtTheColumnLimit() throws Exception {
            when(service.create(any())).thenReturn(
                    new CustomerResponseDTO(UUID.randomUUID(), "334821282", "X"));

            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\""
                            + text(ColumnLimits.CUSTOMER_NAME) + "\"}"))
                    .andExpect(status().isCreated());
        }

        /** La regla de duplicados sigue siendo del servicio; aqui solo se traduce. */
        @Test
        void surfacesTheDuplicateNitMessage() throws Exception {
            when(service.create(any())).thenThrow(new EntityAlreadyExistsException(
                    "Ya existe un cliente registrado con ese nit"));

            mockMvc.perform(post("/v1/customers")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\"Juan Perez\"}"))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.detail")
                            .value("Ya existe un cliente registrado con ese nit"));
        }
    }

    @Nested
    @DisplayName("PATCH /v1/customers/{id}")
    class Update {

        @Test
        void theServiceReceivesTheJsonBodyAndTheIdFromThePath() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(
                    new CustomerResponseDTO(id, "334821282", "NOMBRE NUEVO"));

            mockMvc.perform(patch("/v1/customers/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\",\"name\":\"Nombre Nuevo\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("NOMBRE NUEVO"));

            ArgumentCaptor<UpdateCustomerRequestDTO> captor =
                    ArgumentCaptor.forClass(UpdateCustomerRequestDTO.class);
            verify(service).update(eq(id), captor.capture());

            // Antes llegaban los dos en null y la actualizacion no cambiaba nada.
            assertThat(captor.getValue().nit()).isEqualTo("334821282");
            assertThat(captor.getValue().name()).isEqualTo("Nombre Nuevo");
        }

        /** PATCH es parcial: se puede mandar solo el nombre. */
        @Test
        void acceptsAPartialBodyWithOnlyTheName() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(
                    new CustomerResponseDTO(id, "334821282", "SOLO NOMBRE"));

            mockMvc.perform(patch("/v1/customers/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Solo Nombre\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<UpdateCustomerRequestDTO> captor =
                    ArgumentCaptor.forClass(UpdateCustomerRequestDTO.class);
            verify(service).update(eq(id), captor.capture());

            assertThat(captor.getValue().nit()).isNull();
            assertThat(captor.getValue().name()).isEqualTo("Solo Nombre");
        }

        @Test
        void rejectsAnInvalidNitWithoutTouchingTheService() throws Exception {
            mockMvc.perform(patch("/v1/customers/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"12345\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).update(any(), any());
        }

        @Test
        void rejectsANameLongerThanTheColumn() throws Exception {
            mockMvc.perform(patch("/v1/customers/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + text(ColumnLimits.CUSTOMER_NAME + 1) + "\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).update(any(), any());
        }

        /** Omitir el nombre es la semantica parcial del PATCH: no se toca. */
        @Test
        void anOmittedNameIsValidAndReachesTheServiceAsNull() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(
                    new CustomerResponseDTO(id, "334821282", "SIN CAMBIOS"));

            mockMvc.perform(patch("/v1/customers/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nit\":\"334821282\"}"))
                    .andExpect(status().isOk());

            ArgumentCaptor<UpdateCustomerRequestDTO> captor =
                    ArgumentCaptor.forClass(UpdateCustomerRequestDTO.class);
            verify(service).update(eq(id), captor.capture());
            assertThat(captor.getValue().name()).isNull();
        }

        /**
         * Un cuerpo vacio tambien es un PATCH valido: no cambia nada, pero no es
         * un error del cliente.
         */
        @Test
        void anEmptyBodyIsValid() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(
                    new CustomerResponseDTO(id, "334821282", "SIN CAMBIOS"));

            mockMvc.perform(patch("/v1/customers/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isOk());
        }

        /**
         * El caso que se colaba: `@Size` mide longitud, asi que "   " pasaba y el
         * mapper lo grababa. El cliente acababa sin nombre con una respuesta 200.
         */
        @Test
        void rejectsAWhitespaceOnlyNameWithoutTouchingTheService() throws Exception {
            mockMvc.perform(patch("/v1/customers/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"   \"}"))
                    .andExpect(status().isBadRequest());

            // Si el servicio no se invoca, la entidad no puede mutar.
            verify(service, never()).update(any(), any());
        }

        @Test
        void rejectsANameOfTabsAndNewlines() throws Exception {
            mockMvc.perform(patch("/v1/customers/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"\\t\\n \"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).update(any(), any());
        }

        @Test
        void acceptsANameExactlyAtTheColumnLimit() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(
                    new CustomerResponseDTO(id, "334821282", "X"));

            mockMvc.perform(patch("/v1/customers/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + text(ColumnLimits.CUSTOMER_NAME) + "\"}"))
                    .andExpect(status().isOk());
        }
    }
}
