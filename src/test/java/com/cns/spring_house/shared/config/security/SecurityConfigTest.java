package com.cns.spring_house.shared.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cns.spring_house.auth.controller.AuthController;
import com.cns.spring_house.auth.service.AuthService;
import com.cns.spring_house.jwt.config.JwtConfig;
import com.cns.spring_house.shared.config.cors.CorsConfig;
import com.cns.spring_house.shared.config.cors.CorsProperties;
import com.cns.spring_house.shared.controller.GlobalExceptionHandler;
import com.cns.spring_house.shared.security.CookieUtils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.MappingMatch;

/**
 * Politica de acceso de la API.
 *
 * Antes la cadena terminaba en `anyRequest().permitAll()` y ningun metodo usaba
 * `@PreAuthorize`, asi que toda la API respondia sin credenciales: la unica
 * barrera real era que un token *presente pero invalido* hiciera fallar al
 * resource server. Ahora lo publico es la excepcion declarada y todo lo demas
 * exige autenticacion, de modo que un endpoint nuevo nace protegido.
 *
 * Las rutas se piden como las pide el navegador (con el `/api` de
 * `spring.mvc.servlet.path`) porque Spring Security compara sus patrones contra
 * la URI completa menos el context path, no contra la ruta que ve el controller.
 * Los endpoints de otros modulos no estan cargados en este slice: cuando la
 * seguridad los deja pasar la respuesta no es 200 sino 404/500 del ruteo, de ahi
 * que se afirme "no es 401" en vez de "es 200".
 */
@WebMvcTest(controllers = AuthController.class)
@Import({ SecurityConfig.class, JwtConfig.class, CustomAuthEntryPoint.class,
        CustomAccessDeniedHandler.class, CookieUtils.class, CorsConfig.class,
        GlobalExceptionHandler.class, SecurityConfigTest.EndpointsDePrueba.class })
@EnableConfigurationProperties({ SecurityProperties.class, CorsProperties.class })
@TestPropertySource(properties = {
        "security.access-token.secret-key=clave-de-pruebas-larga-para-hs384-0123456789-0123456789",
        "security.access-token.expiration-time=3600000",
        "security.refresh-token.expiration-time=604800000",
        "app.cors.origin=http://localhost:4200"
})
@MockitoBean(types = JpaMetamodelMappingContext.class)
class SecurityConfigTest {

    /** Igual que `spring.mvc.servlet.path` en application.yml. */
    private static final String SERVLET_PATH = "/api";

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";
    private static final String INVENTORIES = "/api/v1/inventories";
    private static final String SALES = "/api/v1/sales";
    private static final String ENDPOINT_NUEVO = "/api/v1/endpoint-nuevo";
    private static final String ENDPOINT_DE_ADMIN = "/api/v1/endpoint-de-admin";

    private static final String CREDENCIALES = "{\"username\":\"vendedora\",\"password\":\"secreta\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private AuthService authService;

    /**
     * Controllers que no existen en produccion: sirven para comprobar la regla
     * por defecto y la separacion entre autenticacion (401) y autorizacion (403).
     */
    @TestConfiguration
    static class EndpointsDePrueba {

        @RestController
        static class Endpoints {

            /** Sin ninguna anotacion de seguridad, a proposito. */
            @GetMapping("/v1/endpoint-nuevo")
            String nuevo() {
                return "ok";
            }

            @PreAuthorize("hasRole('ADMIN')")
            @GetMapping("/v1/endpoint-de-admin")
            String soloAdmin() {
                return "ok";
            }

        }

    }

    // ── Utilidades ──────────────────────────────────────────────

    /**
     * Reproduce el mapeo real del contenedor: con `spring.mvc.servlet.path=/api`
     * el DispatcherServlet queda publicado en `/api/*`, de modo que la peticion
     * llega con `servletPath=/api` y `pathInfo=/v1/...`. MockMvc no lo hace solo,
     * y sin esto el ruteo buscaria un controller para `/api/v1/...` y ninguna
     * peticion alcanzaria su handler.
     *
     * Es justo la asimetria que obliga a que los matchers lleven el prefijo:
     * Spring Security descuenta solo el context path (vacio aqui) y compara
     * contra `/api/v1/...`, mientras que el `@RequestMapping` ve `/v1/...`.
     */
    private static RequestPostProcessor servletDeApi() {
        return request -> {
            String uri = request.getRequestURI();
            if (!uri.startsWith(SERVLET_PATH)) {
                return request;
            }
            String pathInfo = uri.substring(SERVLET_PATH.length());
            request.setServletPath(SERVLET_PATH);
            request.setPathInfo(pathInfo);
            request.setHttpServletMapping(new MockHttpServletMapping(
                    pathInfo, SERVLET_PATH, "dispatcherServlet", MappingMatch.PATH));
            return request;
        };
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(servletDeApi()));
    }

    private String tokenDe(String role, Instant expiraEn) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS384).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(expiraEn.minus(1, ChronoUnit.HOURS))
                .expiresAt(expiraEn)
                .claim("role", role)
                .claim("workshop", UUID.randomUUID().toString())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String tokenValido(String role) {
        return tokenDe(role, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    /** Vencido con margen: el decodificador tolera 60 s de desfase de reloj. */
    private String tokenVencido() {
        return tokenDe("SELLER", Instant.now().minus(1, ChronoUnit.HOURS));
    }

    private int estadoDe(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult resultado = perform(request).andReturn();
        return resultado.getResponse().getStatus();
    }

    // ── A · login publico ───────────────────────────────────────

    @Nested
    @DisplayName("Endpoints de sesion")
    class Sesion {

        @Test
        void elLoginNoExigeToken() throws Exception {
            when(authService.login(any())).thenReturn(
                    Map.of("accessToken", "nuevo-access", "refreshToken", "nuevo-refresh"));

            perform(post(LOGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREDENCIALES))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("nuevo-access"))
                    .andExpect(header().string(HttpHeaders.SET_COOKIE,
                            org.hamcrest.Matchers.containsString("refreshToken=nuevo-refresh")));
        }

        // ── B · refresh publico respecto al JWT ─────────────────

        @Test
        void elRefreshSeAutenticaConLaCookieYNoConElAccessToken() throws Exception {
            when(authService.refresh("galleta-valida")).thenReturn(
                    Map.of("accessToken", "access-rotado", "refreshToken", "refresh-rotado"));

            perform(post(REFRESH).cookie(new Cookie("refreshToken", "galleta-valida")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-rotado"));
        }

        @Test
        void elLogoutSeAutenticaConLaCookie() throws Exception {
            perform(post(LOGOUT).cookie(new Cookie("refreshToken", "galleta-valida")))
                    .andExpect(status().isNoContent());
        }

        /**
         * El resource server valida el Bearer antes que las reglas de
         * autorizacion, asi que mandar un access token vencido a /refresh lo
         * tumba con 401 aunque la ruta sea publica. Por eso el interceptor de
         * Angular no adjunta Authorization en los endpoints de sesion.
         */
        @Test
        void unBearerVencidoTumbaElRefreshAunqueLaRutaSeaPublica() throws Exception {
            assertThat(estadoDe(post(REFRESH)
                    .cookie(new Cookie("refreshToken", "galleta-valida"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenVencido())))
                    .isEqualTo(401);
        }

    }

    // ── C y D · lo protegido ────────────────────────────────────

    @Nested
    @DisplayName("Endpoints protegidos")
    class Protegidos {

        @Test
        void meExigeAutenticacion() throws Exception {
            assertThat(estadoDe(get(ME))).isEqualTo(401);
        }

        @Test
        void meRespondeConUnBearerValido() throws Exception {
            when(authService.getUserAccount()).thenReturn(null);

            perform(get(ME)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido("SELLER")))
                    .andExpect(status().isOk());
        }

        @Test
        void losEndpointsDeNegocioExigenAutenticacion() throws Exception {
            assertThat(estadoDe(get(INVENTORIES))).isEqualTo(401);
            assertThat(estadoDe(get(SALES))).isEqualTo(401);
        }

        // ── E · access token vencido ────────────────────────────

        @Test
        void unAccessTokenVencidoDevuelve401ParaQueElFrontendRenueve() throws Exception {
            assertThat(estadoDe(get(ME)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenVencido())))
                    .isEqualTo(401);
        }

        @Test
        void unTokenFirmadoConOtraClaveDevuelve401() throws Exception {
            assertThat(estadoDe(get(ME)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-jwt")))
                    .isEqualTo(401);
        }

        // ── 12 · la red de seguridad ────────────────────────────

        @Test
        void unEndpointNuevoSinAnotacionesNaceProtegido() throws Exception {
            assertThat(estadoDe(get(ENDPOINT_NUEVO))).isEqualTo(401);

            perform(get(ENDPOINT_NUEVO)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido("SELLER")))
                    .andExpect(status().isOk());
        }

    }

    // ── F y G · autenticado no es autorizado ────────────────────

    @Nested
    @DisplayName("Method security")
    class MethodSecurity {

        @Test
        void elRolIncorrectoDevuelve403YNo401() throws Exception {
            assertThat(estadoDe(get(ENDPOINT_DE_ADMIN)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido("SELLER"))))
                    .isEqualTo(403);
        }

        @Test
        void elRolCorrectoPasa() throws Exception {
            perform(get(ENDPOINT_DE_ADMIN)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValido("ADMIN")))
                    .andExpect(status().isOk());
        }

        @Test
        void sinTokenElEndpointConRolDevuelve401YNo403() throws Exception {
            assertThat(estadoDe(get(ENDPOINT_DE_ADMIN))).isEqualTo(401);
        }

    }

    // ── H · documentacion ───────────────────────────────────────

    @Nested
    @DisplayName("Swagger")
    class Swagger {

        /**
         * SpringDoc no forma parte de este slice, asi que la ruta no puede
         * responder 200; lo que se comprueba es que la seguridad no la corta.
         */
        @Test
        void laDocumentacionSigueSiendoPublica() throws Exception {
            assertThat(estadoDe(get("/api/v3/api-docs"))).isNotEqualTo(401);
            assertThat(estadoDe(get("/api/swagger-ui/index.html"))).isNotEqualTo(401);
            assertThat(estadoDe(get("/api/swagger-ui.html"))).isNotEqualTo(401);
        }

    }

    // ── Rutas y CORS ────────────────────────────────────────────

    @Nested
    @DisplayName("Rutas y CORS")
    class RutasYCors {

        /**
         * El matcher que habia era `/v1.0/auth/**`, que no lo usa ningun
         * controller: nada bajo esa ruta debe considerarse publico.
         */
        @Test
        void laRutaV1PuntoCeroNoExisteNiEsPublica() throws Exception {
            assertThat(estadoDe(post("/api/v1.0/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREDENCIALES))).isEqualTo(401);
        }

        /** Sin el `/api` del servlet path la regla publica no aplica. */
        @Test
        void elPrefijoDelServletPathFormaParteDelPatron() throws Exception {
            assertThat(estadoDe(post("/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(CREDENCIALES))).isEqualTo(401);
        }

        @Test
        void elPreflightNoQuedaBloqueadoPorLaAutenticacion() throws Exception {
            perform(options(INVENTORIES)
                    .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                    .andExpect(header().string(
                            HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        void elPreflightDeUnOrigenAjenoSeRechaza() throws Exception {
            assertThat(estadoDe(options(INVENTORIES)
                    .header(HttpHeaders.ORIGIN, "http://sitio-malicioso.test")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")))
                    .isEqualTo(403);
        }

        /**
         * `Content-Disposition` no es una cabecera de respuesta simple: sin
         * publicarla, el navegador la recibe pero NO deja leerla desde otro
         * origen, y el cliente pierde el nombre con el que hay que archivar el
         * PDF del cierre de caja, que decide el servidor.
         *
         * Se comprueba sobre una peticion real y no sobre el preflight porque
         * `Access-Control-Expose-Headers` solo viaja en la respuesta real.
         */
        @Test
        void elNombreDelArchivoSePublicaParaQueElClientePuedaLeerlo() throws Exception {
            perform(get(INVENTORIES)
                    .header(HttpHeaders.ORIGIN, "http://localhost:4200"))
                    .andExpect(header().string(
                            HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                            HttpHeaders.CONTENT_DISPOSITION));
        }

    }

}
