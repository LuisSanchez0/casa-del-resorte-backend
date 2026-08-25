package com.cns.spring_house.shared.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.cns.spring_house.shared.config.security.SecurityProperties;

import lombok.RequiredArgsConstructor;

/**
 * Construye la cookie del refresh token. Los atributos `Secure` y `SameSite`
 * salen de la configuracion (`security.cookie.*`) porque no existe un valor
 * unico valido para todos los entornos: `SameSite=None` obliga a `Secure`, y una
 * cookie `Secure` no se guarda sobre http://localhost, asi que fijarlos en el
 * codigo dejaba al navegador descartando la cookie en desarrollo.
 */
@Component
@RequiredArgsConstructor
public class CookieUtils {

    private final SecurityProperties properties;

    public ResponseCookie createCookie(String name, String value, Duration maxAge) {
        return build(name, value, maxAge);
    }

    public ResponseCookie deleteCookie(String name) {
        return build(name, "", Duration.ZERO);
    }

    private ResponseCookie build(String name, String value, Duration maxAge) {
        SecurityProperties.Cookie cookie = properties.getCookie();
        return ResponseCookie
                .from(name, value)
                .httpOnly(true)
                .secure(cookie.isSecure())
                .path("/")
                .maxAge(maxAge)
                .sameSite(cookie.getSameSite())
                .build();
    }

}
