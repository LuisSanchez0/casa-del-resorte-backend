package com.cns.spring_house.model.controller;

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

import java.util.Map;
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

import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;
import com.cns.spring_house.model.service.ModelService;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.exception.ValidationException;

/**
 * El contrato HTTP del intervalo de años.
 *
 * El campo `year` ya no existe: un modelo declara los dos extremos del
 * intervalo al que aplica, y "2020" se envia como 2020/2020. Lo que estas
 * pruebas fijan es que el cuerpo no admite el atajo ambiguo —ni "2020-2025" ni
 * el `year` de antes— y que un PATCH puede mover un solo extremo.
 */
@ExtendWith(MockitoExtension.class)
class ModelControllerTest {

    private static final String BRAND = "6c1f2f4e-0000-0000-0000-000000000000";

    @Mock
    private ModelService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ModelController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Sin marca: estas pruebas son del intervalo de anos, no de la marca. */
    private static ModelResponseDTO response(int from, int to) {
        return new ModelResponseDTO(UUID.randomUUID(), "Corolla", from, to, null);
    }

    @Nested
    @DisplayName("POST /v1/models")
    class Create {

        @Test
        void createsAModelForASingleYear() throws Exception {
            when(service.create(any())).thenReturn(response(2020, 2020));

            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearFrom\":2020,\"yearTo\":2020}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.yearFrom").value(2020))
                    .andExpect(jsonPath("$.yearTo").value(2020));

            ArgumentCaptor<CreateModelRequestDTO> captor =
                    ArgumentCaptor.forClass(CreateModelRequestDTO.class);
            verify(service).create(captor.capture());
            assertThat(captor.getValue().yearFrom()).isEqualTo(2020);
            assertThat(captor.getValue().yearTo()).isEqualTo(2020);
        }

        @Test
        void createsAModelForAYearRange() throws Exception {
            when(service.create(any())).thenReturn(response(2020, 2025));

            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearFrom\":2020,\"yearTo\":2025}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.yearFrom").value(2020))
                    .andExpect(jsonPath("$.yearTo").value(2025));
        }

        /** La respuesta lleva SIEMPRE los dos extremos, nunca una cadena. */
        @Test
        void theResponseExposesBothEndsAndNoFormattedString() throws Exception {
            when(service.create(any())).thenReturn(response(2020, 2025));

            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearFrom\":2020,\"yearTo\":2025}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.year").doesNotExist())
                    .andExpect(jsonPath("$.yearFrom").isNumber())
                    .andExpect(jsonPath("$.yearTo").isNumber());
        }

        @Test
        void rejectsAMissingEndOfTheRange() throws Exception {
            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearFrom\":2020}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearTo\":2025}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        /**
         * El cuerpo antiguo ya no vale ni a medias: `year` no rellena nada y el
         * alta se rechaza por falta de intervalo, en vez de guardar un modelo
         * con años inventados.
         */
        @Test
        void rejectsTheLegacySingleYearBody() throws Exception {
            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"year\":2020}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        /** Ninguna forma de colar "2020-2025" como un año. */
        @Test
        void rejectsAFormattedRangeAsAYear() throws Exception {
            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearFrom\":\"2020-2025\",\"yearTo\":\"2020-2025\"}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).create(any());
        }

        /** El intervalo al reves lo rechaza el servicio, y sale como un 400. */
        @Test
        void surfacesAnInvertedRangeAsABadRequest() throws Exception {
            String message = "El año final no puede ser menor que el año inicial";
            when(service.create(any())).thenThrow(
                    new ValidationException(message, Map.of("yearTo", message)));

            mockMvc.perform(post("/v1/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brand\":\"" + BRAND
                            + "\",\"name\":\"Corolla\",\"yearFrom\":2025,\"yearTo\":2020}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.yearTo").value(message));
        }
    }

    @Nested
    @DisplayName("PATCH /v1/models/{id}")
    class Update {

        @Test
        void widensASingleYearIntoARange() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(response(2020, 2025));

            mockMvc.perform(patch("/v1/models/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yearTo\":2025}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yearFrom").value(2020))
                    .andExpect(jsonPath("$.yearTo").value(2025));

            // Lo que no viaja sigue siendo null: el servicio lo fusiona con la
            // fila, y ese es justo el caso que puede invertir el intervalo.
            ArgumentCaptor<UpdateModelRequestDTO> captor =
                    ArgumentCaptor.forClass(UpdateModelRequestDTO.class);
            verify(service).update(eq(id), captor.capture());
            assertThat(captor.getValue().yearFrom()).isNull();
            assertThat(captor.getValue().yearTo()).isEqualTo(2025);
        }

        @Test
        void narrowsARangeIntoASingleYear() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(response(2020, 2020));

            mockMvc.perform(patch("/v1/models/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yearFrom\":2020,\"yearTo\":2020}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yearFrom").value(2020))
                    .andExpect(jsonPath("$.yearTo").value(2020));
        }

        @Test
        void movesBothEndsOfTheRange() throws Exception {
            UUID id = UUID.randomUUID();
            when(service.update(eq(id), any())).thenReturn(response(2022, 2024));

            mockMvc.perform(patch("/v1/models/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yearFrom\":2022,\"yearTo\":2024}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yearFrom").value(2022))
                    .andExpect(jsonPath("$.yearTo").value(2024));
        }

        /** Un extremo por debajo del minimo del esquema no llega al servicio. */
        @Test
        void rejectsAYearBelowTheSchemaMinimum() throws Exception {
            mockMvc.perform(patch("/v1/models/" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yearFrom\":1899}"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).update(any(), any());
        }

        @Test
        void surfacesAnInvertedMergedRangeAsABadRequest() throws Exception {
            UUID id = UUID.randomUUID();
            String message = "El año final no puede ser menor que el año inicial";
            when(service.update(eq(id), any())).thenThrow(
                    new ValidationException(message, Map.of("yearTo", message)));

            mockMvc.perform(patch("/v1/models/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yearTo\":2019}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.yearTo").value(message));
        }
    }

}
