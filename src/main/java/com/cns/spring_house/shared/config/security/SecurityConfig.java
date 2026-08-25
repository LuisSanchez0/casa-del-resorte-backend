package com.cns.spring_house.shared.config.security;

import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.PathPatternRequestMatcherBuilderFactoryBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Endpoints de sesión. Son los únicos que no pueden exigir un access token
     * válido: `login` porque todavía no existe, y `refresh`/`logout` porque se
     * invocan justo cuando venció. Su credencial real es la cookie httpOnly
     * `refreshToken`, que valida `AuthServiceImpl`. Se listan uno por uno —y no
     * como `/v1/auth/**`— para que `GET /v1/auth/me` siga exigiendo autenticación.
     */
    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/v1/auth/login",
            "/v1/auth/refresh",
            "/v1/auth/logout"
    };

    /** Documentación de la API, pública como hasta ahora. */
    private static final String[] PUBLIC_DOC_ENDPOINTS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private final JwtAuthenticationConverter authenticationConverter;
    private final CustomAuthEntryPoint authEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    SecurityFilterChain customSecurityFilterChain(HttpSecurity security) {
        security
                .csrf(csrf -> csrf.disable())
                // Sin esto el preflight OPTIONS moriría en `authenticated()` antes de
                // llegar al DispatcherServlet, que es donde vive `CorsConfig`. No
                // duplica configuración: Spring Security reutiliza la de `addCorsMappings`.
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ENDPOINTS)
                        .permitAll()
                        .requestMatchers(PUBLIC_DOC_ENDPOINTS)
                        .permitAll()
                        // Regla segura por defecto: un endpoint nuevo nace protegido
                        // aunque a nadie se le ocurra anotarlo.
                        .anyRequest()
                        .authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(authenticationConverter)))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return security.build();
    }

    /**
     * Spring Security compara sus patrones contra la URI completa menos el
     * context path, así que el `spring.mvc.servlet.path` (/api) forma parte de la
     * ruta que ve —a diferencia de los `@RequestMapping`, que sí lo tienen
     * descontado—. Publicando el prefijo aquí, los matchers de arriba se escriben
     * igual que los controllers y siguen apuntando al mismo sitio si mañana
     * cambia el servlet path.
     */
    @Bean
    PathPatternRequestMatcherBuilderFactoryBean customPathPatternRequestMatcherBuilder(
            DispatcherServletPath dispatcherServletPath) {
        PathPatternRequestMatcherBuilderFactoryBean factoryBean =
                new PathPatternRequestMatcherBuilderFactoryBean();
        String prefix = dispatcherServletPath.getPrefix();
        if (StringUtils.hasText(prefix)) {
            factoryBean.setBasePath(prefix);
        }
        return factoryBean;
    }

    @Bean
    PasswordEncoder customPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

}
