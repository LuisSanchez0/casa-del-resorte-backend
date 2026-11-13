package com.cns.spring_house.model.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.cns.spring_house.brand.controller.BrandController;
import com.cns.spring_house.brand.service.BrandService;
import com.cns.spring_house.jwt.config.JwtConfig;
import com.cns.spring_house.model.service.ModelService;
import com.cns.spring_house.shared.config.cors.CorsConfig;
import com.cns.spring_house.shared.config.cors.CorsProperties;
import com.cns.spring_house.shared.config.security.CustomAccessDeniedHandler;
import com.cns.spring_house.shared.config.security.CustomAuthEntryPoint;
import com.cns.spring_house.shared.config.security.SecurityConfig;
import com.cns.spring_house.shared.config.security.SecurityProperties;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.security.CookieUtils;

import jakarta.servlet.http.MappingMatch;

/**
 * QUIEN puede cambiar el catalogo de marcas y modelos, por HTTP y con la cadena
 * de seguridad real: token JWT firmado, `SecurityConfig`, `@PreAuthorize` y el
 * manejador global que traduce las denegaciones.
 *
 * El catalogo es GLOBAL —las tablas no tienen taller—, asi que la unica
 * frontera es el rol. Lo administra Control, que tiene la pantalla de marcas y
 * modelos, y ADMIN, igual que proveedores: dar de alta, editar y eliminar. La
 * lectura queda abierta a cualquier sesion, porque la caja, el taller y el
 * historial la necesitan. Sin sesion, 401 y no 403, para que el frontend
 * renueve el token en lugar de mostrar "sin permiso".
 *
 * Antes de esta prueba el alta y la edicion no tenian `@PreAuthorize`: un
 * vendedor o un policia podian crear o renombrar marcas y modelos llamando a la
 * API directamente, aunque ninguna pantalla suya lo ofreciera.
 */
@WebMvcTest(controllers = { BrandController.class, ModelController.class })
@Import({ SecurityConfig.class, JwtConfig.class, CustomAuthEntryPoint.class,
        CustomAccessDeniedHandler.class, CookieUtils.class, CorsConfig.class,
        GlobalExceptionHandler.class })
@EnableConfigurationProperties({ SecurityProperties.class, CorsProperties.class })
@TestPropertySource(properties = {
        "security.access-token.secret-key=clave-de-pruebas-larga-para-hs384-0123456789-0123456789",
        "security.access-token.expiration-time=3600000",
        "security.refresh-token.expiration-time=604800000",
        "app.cors.origin=http://localhost:4200"
})
@MockitoBean(types = JpaMetamodelMappingContext.class)
class CatalogAuthorizationHttpTest {

    private static final String SERVLET_PATH = "/api";
    private static final String BRANDS = "/api/v1/brands";
    private static final String MODELS = "/api/v1/models";

    private static final List<String> CATALOG_MANAGERS = List.of("CONTROL", "ADMIN");
    private static final List<String> OTHER_ROLES = List.of("SELLER", "OPERATOR", "POLICE", "SECRETARY");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtEncoder jwtEncoder;

    @MockitoBean private BrandService brandService;
    @MockitoBean private ModelService modelService;

    // ── Lectura ──────────────────────────────────────────────────

    @Test
    void everyRoleCanStillListAndReadBrandsAndModels() throws Exception {
        for (String role : allRoles()) {
            for (Map.Entry<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> read : reads().entrySet()) {
                assertThat(status(read.getValue().get(), role))
                        .as("%s con rol %s", read.getKey(), role)
                        .isEqualTo(200);
            }
        }
    }

    // ── Escritura: alta, edicion, eliminacion ───────────────────

    @Test
    void controlAndAdminCanCreateEditAndDelete() throws Exception {
        Map<String, Integer> expected = Map.of(
                "POST marca", 201, "PATCH marca", 200, "DELETE marca", 204,
                "POST modelo", 201, "PATCH modelo", 200, "DELETE modelo", 204);

        for (String role : CATALOG_MANAGERS) {
            for (Map.Entry<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> write : writes().entrySet()) {
                assertThat(status(write.getValue().get(), role))
                        .as("%s con rol %s", write.getKey(), role)
                        .isEqualTo(expected.get(write.getKey()));
            }
        }

        verify(brandService, times(2)).create(any());
        verify(brandService, times(2)).update(any(), any());
        verify(brandService, times(2)).delete(any());
        verify(modelService, times(2)).create(any());
        verify(modelService, times(2)).update(any(), any());
        verify(modelService, times(2)).delete(any());
    }

    @Test
    void everyOtherRoleGets403AndNeverReachesTheService() throws Exception {
        for (String role : OTHER_ROLES) {
            for (Map.Entry<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> write : writes().entrySet()) {
                assertThat(status(write.getValue().get(), role))
                        .as("%s con rol %s", write.getKey(), role)
                        .isEqualTo(403);
            }
        }

        assertNoCatalogWrite();
    }

    @Test
    void withoutATokenEveryOperationIs401NotForbidden() throws Exception {
        Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> all = new LinkedHashMap<>(reads());
        all.putAll(writes());

        for (Map.Entry<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> request : all.entrySet()) {
            assertThat(status(request.getValue().get(), null))
                    .as("%s sin token", request.getKey())
                    .isEqualTo(401);
        }

        assertNoCatalogWrite();
    }

    /**
     * No existe otra forma de escribir: ni PUT, ni una ruta de restaurar. Ni
     * siquiera quien administra el catalogo la encuentra.
     */
    @Test
    void thereIsNoPutNorAnyRestoreRoute() throws Exception {
        UUID id = UUID.randomUUID();
        for (String base : List.of(BRANDS, MODELS)) {
            assertThat(status(put(base + "/" + id)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"deleted\":false}"), "CONTROL"))
                    .as("PUT %s", base)
                    .isEqualTo(405);
            for (String restore : List.of("/restore", "/reactivate")) {
                assertThat(status(post(base + "/" + id + restore), "ADMIN") / 100)
                        .as("POST %s%s no es un 2xx", base, restore)
                        .isNotEqualTo(2);
            }
        }
        assertNoCatalogWrite();
    }

    // ── Peticiones ───────────────────────────────────────────────

    private Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> reads() {
        UUID id = UUID.randomUUID();
        Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> reads = new LinkedHashMap<>();
        reads.put("GET marcas", () -> get(BRANDS));
        reads.put("GET marca", () -> get(BRANDS + "/" + id));
        reads.put("GET modelos", () -> get(MODELS).param("brand", UUID.randomUUID().toString()));
        reads.put("GET modelo", () -> get(MODELS + "/" + id));
        return reads;
    }

    /** Peticiones VALIDAS: la respuesta solo puede depender del rol. */
    private Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> writes() {
        UUID id = UUID.randomUUID();
        Map<String, Supplier<AbstractMockHttpServletRequestBuilder<?>>> writes = new LinkedHashMap<>();
        writes.put("POST marca", () -> multipart(BRANDS)
                .file(new MockMultipartFile("logo", "logo.png", "image/png", new byte[] { 1, 2, 3 }))
                .param("name", "TOYOTA"));
        writes.put("PATCH marca", () -> multipart(HttpMethod.PATCH, BRANDS + "/" + id)
                .param("name", "TOYOTA MOTORS"));
        writes.put("DELETE marca", () -> delete(BRANDS + "/" + id));
        writes.put("POST modelo", () -> post(MODELS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"brand\":\"" + UUID.randomUUID()
                        + "\",\"name\":\"HILUX\",\"yearFrom\":2020,\"yearTo\":2022}"));
        writes.put("PATCH modelo", () -> patch(MODELS + "/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"HILUX II\"}"));
        writes.put("DELETE modelo", () -> delete(MODELS + "/" + id));
        return writes;
    }

    private void assertNoCatalogWrite() {
        verify(brandService, never()).create(any());
        verify(brandService, never()).update(any(), any());
        verify(brandService, never()).delete(any());
        verify(modelService, never()).create(any());
        verify(modelService, never()).update(any(), any());
        verify(modelService, never()).delete(any());
        clearInvocations(brandService, modelService);
    }

    private static List<String> allRoles() {
        return List.of("CONTROL", "ADMIN", "SELLER", "OPERATOR", "POLICE", "SECRETARY");
    }

    // ── Utilidades (las mismas que SecurityConfigTest) ──────────

    private int status(AbstractMockHttpServletRequestBuilder<?> request, String role) throws Exception {
        if (role != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(role));
        }
        return mockMvc.perform(request.with(apiServlet())).andReturn().getResponse().getStatus();
    }

    private String tokenFor(String role) {
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(expiresAt.minus(1, ChronoUnit.HOURS))
                .expiresAt(expiresAt)
                .claim("role", role)
                .claim("workshop", UUID.randomUUID().toString())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS384).build(), claims)).getTokenValue();
    }

    /** `spring.mvc.servlet.path=/api`: ver SecurityConfigTest#servletDeApi. */
    private static RequestPostProcessor apiServlet() {
        return request -> {
            String pathInfo = request.getRequestURI().substring(SERVLET_PATH.length());
            request.setServletPath(SERVLET_PATH);
            request.setPathInfo(pathInfo);
            request.setHttpServletMapping(new MockHttpServletMapping(
                    pathInfo, SERVLET_PATH, "dispatcherServlet", MappingMatch.PATH));
            return request;
        };
    }
}
