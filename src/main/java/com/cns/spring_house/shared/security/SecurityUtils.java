package com.cns.spring_house.shared.security;

import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public class SecurityUtils {

    private SecurityUtils() {

    }

    public static Jwt getJwt() {
        Authentication auth = SecurityContextHolder
                .getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        throw new BadCredentialsException("El token es invalido");
    }

    public static UUID getId() {
        Jwt jwt = getJwt();
        UUID id = UUID.fromString(jwt.getSubject());
        if (id == null) {
            throw new BadCredentialsException("El token es invalido");
        }
        return id;
    }

    public static UUID getWorkshopId() {
        Jwt jwt = getJwt();
        UUID workshopId = jwt.getClaim("workshop");
        if (workshopId == null) {
            throw new BadCredentialsException("El token es invalido");
        }
        return workshopId;
    }

}
