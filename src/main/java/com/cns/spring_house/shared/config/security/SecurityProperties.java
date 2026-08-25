package com.cns.spring_house.shared.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@ConfigurationProperties(prefix = "security")
@Component
@Getter
@Setter
public class SecurityProperties {

    private Token refreshToken = new Token();
    private JwtToken accessToken = new JwtToken();
    private Cookie cookie = new Cookie();

    @Getter
    @Setter
    public static class Token {

        private long expirationTime;

    }

    /**
     * Atributos de la cookie del refresh token. Se configuran por entorno porque
     * los navegadores descartan `SameSite=None` sin `Secure`: en desarrollo
     * (http://localhost) la combinacion valida es `Lax` sin `Secure`, y en
     * produccion, con el frontend en otro dominio, `None` con `Secure`.
     */
    @Getter
    @Setter
    public static class Cookie {

        private boolean secure;
        private String sameSite = "Lax";

    }

    @Getter
    @Setter
    public static class JwtToken extends Token {

        private String secretKey;

    }

}
