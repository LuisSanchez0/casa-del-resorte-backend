package com.cns.spring_house.transformation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.shared.constants.ColumnLimits;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.service.TransformationService;

/**
 * El nombre de la pieza resultante entra por HTTP, así que su contrato se
 * comprueba donde el cliente lo envía: qué llega al DTO y qué se rechaza antes
 * de tocar nada.
 *
 * Un nombre demasiado largo tiene que morir en la validación de la petición. Si
 * llegara al servicio, la transformación consumiría la unidad de entrada y
 * crearía productos e inventarios para terminar reventando en el INSERT contra
 * `product.name VARCHAR(100)`: un 500 y stock movido por un error del cliente.
 */
@ExtendWith(MockitoExtension.class)
class TransformationOutputNameValidationTest {

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
    @DisplayName("El nombre personalizado llega al DTO tal y como se envió")
    void theCustomNameReachesTheDto() throws Exception {
        when(service.create(any())).thenReturn(null);

        mockMvc.perform(post("/v1/transformations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"Hoja reforzada especial\"")))
                .andExpect(status().isCreated());

        assertThat(receivedRequest().outputName()).isEqualTo("Hoja reforzada especial");
    }

    /** Espacios a los lados y una cadena en blanco no son un nombre. */
    @Test
    void aBlankNameArrivesAsNull() throws Exception {
        when(service.create(any())).thenReturn(null);

        mockMvc.perform(post("/v1/transformations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"   \"")))
                .andExpect(status().isCreated());

        assertThat(receivedRequest().outputName()).isNull();
    }

    @Test
    void anAbsentNameArrivesAsNull() throws Exception {
        when(service.create(any())).thenReturn(null);

        mockMvc.perform(post("/v1/transformations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "inputItem": "%s",
                              "outputAttributes": { "largo": 900 }
                            }
                            """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());

        assertThat(receivedRequest().outputName()).isNull();
    }

    /**
     * El límite es el de la columna, no uno inventado aquí: exactamente
     * `ColumnLimits.PRODUCT_NAME` caracteres pasan y uno más se rechaza.
     */
    @Test
    void theLongestAllowedNameIsAccepted() throws Exception {
        when(service.create(any())).thenReturn(null);
        String name = "N".repeat(ColumnLimits.PRODUCT_NAME);

        mockMvc.perform(post("/v1/transformations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"" + name + "\"")))
                .andExpect(status().isCreated());

        assertThat(receivedRequest().outputName()).hasSize(ColumnLimits.PRODUCT_NAME);
    }

    @Test
    @DisplayName("Un nombre más largo que la columna se rechaza sin crear nada")
    void aTooLongNameIsRejectedBeforeTouchingAnything() throws Exception {
        String name = "N".repeat(ColumnLimits.PRODUCT_NAME + 1);

        mockMvc.perform(post("/v1/transformations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"" + name + "\"")))
                .andExpect(status().isBadRequest());

        // Nunca se llamó al servicio: no hay producto creado, ni inventario, ni
        // unidad de entrada consumida.
        verifyNoInteractions(service);
    }

    private String body(String outputName) {
        return """
            {
              "inputItem": "%s",
              "outputAttributes": { "largo": 900 },
              "outputName": %s
            }
            """.formatted(UUID.randomUUID(), outputName);
    }

    private CreateTransformationRequestDTO receivedRequest() {
        ArgumentCaptor<CreateTransformationRequestDTO> received =
                ArgumentCaptor.forClass(CreateTransformationRequestDTO.class);
        org.mockito.Mockito.verify(service).create(received.capture());
        return received.getValue();
    }

}
