package com.cns.spring_house.shared.security;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Jwt getJwt() {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken();
        }

        throw new BadCredentialsException(
                "No existe una autenticación JWT válida"
        );
    }

    public static UUID getId() {
        Jwt jwt = getJwt();
        String subject = jwt.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new BadCredentialsException(
                    "El token no contiene el identificador del usuario"
            );
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException(
                    "El identificador del usuario contenido en el token no es válido",
                    exception
            );
        }
    }

    public static UUID getWorkshopId() {
        Jwt jwt = getJwt();

        String workshopId = jwt.getClaimAsString(
                "workshop"
        );

        if (workshopId == null || workshopId.isBlank()) {
            throw new BadCredentialsException(
                    "El token no contiene el identificador del taller"
            );
        }

        try {
            return UUID.fromString(workshopId);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException(
                    "El identificador del taller contenido en el token no es válido",
                    exception
            );
        }
    }

    /**
     * Prefijo que {@code JwtConfig} antepone a la claim `role` al convertirla en
     * autoridad. Se nombra aquí para que la comparación use exactamente lo que
     * hay en el contexto y no una versión escrita a mano.
     */
    private static final String ROLE_PREFIX = "ROLE_";

    /**
     * Si el usuario autenticado tiene alguno de los roles indicados, escritos sin
     * prefijo (`"POLICE"`, `"ADMIN"`), igual que en las expresiones `hasRole`.
     *
     * NO sustituye a `@PreAuthorize`: eso decide si una petición se atiende o se
     * rechaza con 403. Esto responde una pregunta distinta —qué parte de una
     * respuesta que el usuario SÍ puede pedir le corresponde ver—, y por eso vive
     * en el servicio y no en la anotación del controlador.
     *
     * Se lee de las autoridades del contexto y no de la claim en crudo: son las
     * mismas que evalúan las expresiones de seguridad, así que las dos vías no
     * pueden discrepar.
     *
     * Devuelve false, y no una excepción, cuando no hay autenticación: quien
     * exige que la haya es la cadena de filtros, y una comprobación de visibilidad
     * que falle en abierto sería justo el error contrario al que evita.
     */
    public static boolean hasAnyRole(String... roles) {
        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        Set<String> granted = authentication
                .getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        return Arrays
                .stream(roles)
                .anyMatch(role -> granted.contains(ROLE_PREFIX + role));
    }
}