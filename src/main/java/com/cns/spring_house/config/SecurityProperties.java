package com.cns.spring_house.config;

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

    @Getter
    @Setter
    public static class Token {

        private long expirationTime;

    }

    @Getter
    @Setter
    public static class JwtToken extends Token {

        private String secretKey;

    }

}
