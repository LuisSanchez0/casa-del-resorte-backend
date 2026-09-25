package com.cns.spring_house.transformation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.transformation.dto.SearchTransformationRequestDTO;
import com.cns.spring_house.transformation.service.TransformationService;

/**
 * El alcance del historial entra por HTTP, así que se comprueba donde el cliente
 * lo envía.
 *
 * `?workshop=...` era un parámetro real: omitirlo devolvía las transformaciones
 * de TODOS los talleres y ponerlo con el UUID de otro devolvía las de ese otro.
 * El DTO ya no declara el campo y el servicio toma el taller del token, de modo
 * que enviarlo no puede hacer nada.
 *
 * Se comprueba además que enviarlo NO rompe la petición: los clientes que ya lo
 * mandan —el panel de operador lo hace— siguen funcionando exactamente igual,
 * simplemente se ignora.
 */
@ExtendWith(MockitoExtension.class)
class TransformationSearchScopeTest {

    @Mock
    private TransformationService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TransformationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("El DTO de búsqueda no tiene ningún campo de taller")
    void theSearchDtoCarriesNoWorkshop() {
        assertThat(SearchTransformationRequestDTO.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("employee", "minDate", "maxDate")
                .doesNotContain("workshop");
    }

    @Test
    @DisplayName("Un ?workshop= de otro taller se ignora y no llega al servicio")
    void aForeignWorkshopParameterIsIgnored() throws Exception {
        when(service.getAll(any())).thenReturn(List.of());
        UUID foreignWorkshop = UUID.randomUUID();

        mockMvc.perform(get("/v1/transformations")
                        .param("workshop", foreignWorkshop.toString()))
                // No revienta: el cliente actual ya envía el parámetro.
                .andExpect(status().isOk());

        ArgumentCaptor<SearchTransformationRequestDTO> captured =
                ArgumentCaptor.forClass(SearchTransformationRequestDTO.class);
        org.mockito.Mockito.verify(service).getAll(captured.capture());

        // Nada del parámetro sobrevive: no hay dónde guardarlo.
        assertThat(captured.getValue().toString())
                .doesNotContain(foreignWorkshop.toString());
        assertThat(captured.getValue().employee()).isNull();
        assertThat(captured.getValue().minDate()).isNull();
        assertThat(captured.getValue().maxDate()).isNull();
    }

    @Test
    @DisplayName("Los filtros que sí existen siguen llegando")
    void theRealFiltersStillReachTheService() throws Exception {
        when(service.getAll(any())).thenReturn(List.of());
        UUID employee = UUID.randomUUID();

        mockMvc.perform(get("/v1/transformations")
                        .param("employee", employee.toString())
                        .param("minDate", "2026-09-01T00:00:00")
                        .param("maxDate", "2026-09-30T23:59:59"))
                .andExpect(status().isOk());

        ArgumentCaptor<SearchTransformationRequestDTO> captured =
                ArgumentCaptor.forClass(SearchTransformationRequestDTO.class);
        org.mockito.Mockito.verify(service).getAll(captured.capture());

        assertThat(captured.getValue().employee()).isEqualTo(employee);
        assertThat(captured.getValue().minDate()).isNotNull();
        assertThat(captured.getValue().maxDate()).isNotNull();
    }
}
