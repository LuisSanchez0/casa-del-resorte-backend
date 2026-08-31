package com.cns.spring_house.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cns.spring_house.product.dto.ProductResponseDTO;
import com.cns.spring_house.product.dto.SearchProductRequestDTO;
import com.cns.spring_house.product.service.ProductService;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;

/**
 * El contrato HTTP de `GET /v1/products`, y en concreto que responde el
 * servidor ante una MARCA que no es un identificador.
 *
 * La comprobacion vive en el DTO, pero lo que hay que fijar aqui es la otra
 * mitad: que esa comprobacion llega al cliente como un 400 con un mensaje
 * legible y no como un 500 con la traza dentro. Una prueba unitaria del DTO no
 * puede verlo —solo ve la excepcion—, y la diferencia entre ambas respuestas
 * esta entera en el paso por {@link GlobalExceptionHandler}.
 *
 * El motivo por el que el 400 importa mas que el codigo: antes la marca
 * ilegible se descartaba y la busqueda seguia sin filtrar por marca. Desde la
 * caja eso es indistinguible de haber buscado bien, asi que el vendedor se
 * lleva a la pantalla piezas de todas las marcas creyendo que son las de una.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerTest {

    @Mock
    private ProductService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ArgumentCaptor<SearchProductRequestDTO> captureSearch() {
        ArgumentCaptor<SearchProductRequestDTO> captor =
                ArgumentCaptor.forClass(SearchProductRequestDTO.class);
        verify(service).getAll(captor.capture());
        return captor;
    }

    @Nested
    @DisplayName("Marca mal escrita")
    class MalformedBrand {

        /**
         * Los cuatro textos que manda de verdad una caja rota: el nombre en
         * lugar del identificador, y los tres que produce javascript al
         * serializar un valor que no tiene.
         */
        @ParameterizedTest
        @ValueSource(strings = { "toyota", "undefined", "null", "[object Object]" })
        void aBrandThatIsNotAUuidIsRejectedWith400(String malformed) throws Exception {
            mockMvc.perform(get("/v1/products").param("brand", malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("El identificador de la marca no es válido."))
                    .andExpect(jsonPath("$.title").value("Validation Error"))
                    .andExpect(jsonPath("$.error_category").value("Validation"));

            verify(service, never()).getAll(any());
        }

        /**
         * Ni traza ni mensaje de Hibernate ni 500. Es lo que distingue un
         * parametro rechazado de un servidor roto.
         */
        @Test
        void theRejectionCarriesNoStackTrace() throws Exception {
            String body = mockMvc.perform(get("/v1/products").param("brand", "toyota"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.trace").doesNotExist())
                    .andExpect(jsonPath("$.stackTrace").doesNotExist())
                    .andExpect(jsonPath("$.exception").doesNotExist())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(body).doesNotContain("IllegalArgumentException");
            assertThat(body).doesNotContain("com.cns.spring_house");
        }

        /**
         * La marca ilegible NO puede colarse como caracteristica: si lo hiciera,
         * la consulta acabaria en un `jsonb_extract_path_text(attributes,
         * 'brand') = 'toyota'` y el 400 se habria cambiado por un 200 vacio,
         * que es el mismo enganno de antes con otra forma.
         */
        @Test
        void aMalformedBrandIsNotReinterpretedAsAProductAttribute() throws Exception {
            mockMvc.perform(get("/v1/products")
                    .param("brand", "toyota")
                    .param("largo", "15"))
                    .andExpect(status().isBadRequest());

            verify(service, never()).getAll(any());
        }
    }

    @Nested
    @DisplayName("Marca bien escrita")
    class WellFormedBrand {

        @Test
        void aValidBrandReachesTheServiceAsACriterion() throws Exception {
            UUID brand = UUID.randomUUID();
            when(service.getAll(any())).thenReturn(List.of());

            mockMvc.perform(get("/v1/products").param("brand", brand.toString()))
                    .andExpect(status().isOk());

            SearchProductRequestDTO search = captureSearch().getValue();
            assertThat(search.brand()).isEqualTo(brand);
            assertThat(search.attributes()).doesNotContainKey("brand");
        }

        /**
         * Un identificador con forma correcta que no existe no es un error de
         * quien pregunta: se busca y no se encuentra nada.
         */
        @Test
        void aWellFormedBrandThatMatchesNothingAnswers200AndEmpty() throws Exception {
            when(service.getAll(any())).thenReturn(List.<ProductResponseDTO>of());

            mockMvc.perform(get("/v1/products")
                    .param("brand", UUID.randomUUID().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }

        /** `?brand=` es un formulario que no ha elegido marca, no un error. */
        @Test
        void aBlankBrandIsReadAsNoFilter() throws Exception {
            when(service.getAll(any())).thenReturn(List.of());

            mockMvc.perform(get("/v1/products").param("brand", ""))
                    .andExpect(status().isOk());

            SearchProductRequestDTO search = captureSearch().getValue();
            assertThat(search.brand()).isNull();
            assertThat(search.attributes()).doesNotContainKey("brand");
        }

        @Test
        void anAbsentBrandIsNoFilterAtAll() throws Exception {
            when(service.getAll(any())).thenReturn(List.of());

            mockMvc.perform(get("/v1/products").param("q", "hoja"))
                    .andExpect(status().isOk());

            SearchProductRequestDTO search = captureSearch().getValue();
            assertThat(search.q()).isEqualTo("hoja");
            assertThat(search.brand()).isNull();
        }
    }

    @Nested
    @DisplayName("La marca convive con el resto de criterios")
    class Regressions {

        /**
         * Marca y modelo son INDEPENDIENTES: los dos a la vez tienen que llegar
         * como dos criterios, no como uno que anula al otro.
         */
        @Test
        void brandAndModelTravelTogether() throws Exception {
            UUID brand = UUID.randomUUID();
            UUID model = UUID.randomUUID();
            when(service.getAll(any())).thenReturn(List.of());

            mockMvc.perform(get("/v1/products")
                    .param("brand", brand.toString())
                    .param("model", model.toString()))
                    .andExpect(status().isOk());

            SearchProductRequestDTO search = captureSearch().getValue();
            assertThat(search.brand()).isEqualTo(brand);
            assertThat(search.model()).isEqualTo(model);
            assertThat(search.attributes()).isEmpty();
        }

        /**
         * Reservar la marca no puede llevarse por delante los filtros que si
         * son caracteristicas de la familia.
         */
        @Test
        void familyAndBrandLeaveTheDynamicAttributesUntouched() throws Exception {
            UUID family = UUID.randomUUID();
            UUID brand = UUID.randomUUID();
            when(service.getAll(any())).thenReturn(List.of());

            mockMvc.perform(get("/v1/products")
                    .param("family", family.toString())
                    .param("brand", brand.toString())
                    .param("largo", "15")
                    .param("tieneOrificio", "true"))
                    .andExpect(status().isOk());

            SearchProductRequestDTO search = captureSearch().getValue();
            assertThat(search.family()).isEqualTo(family);
            assertThat(search.brand()).isEqualTo(brand);
            assertThat(search.attributes())
                    .containsOnly(
                            Map.entry("largo", "15"),
                            Map.entry("tieneOrificio", "true"));
        }

        /**
         * El fallo silencioso estaba en el lector compartido, asi que familia y
         * modelo mal escritos se rechazan por el mismo camino.
         */
        @Test
        void aMalformedFamilyOrModelIsAlsoRejected() throws Exception {
            mockMvc.perform(get("/v1/products").param("family", "hojas"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("El identificador de la familia no es válido."));

            mockMvc.perform(get("/v1/products").param("model", "corolla"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail")
                            .value("El identificador del modelo no es válido."));

            verify(service, never()).getAll(any());
        }
    }
}
