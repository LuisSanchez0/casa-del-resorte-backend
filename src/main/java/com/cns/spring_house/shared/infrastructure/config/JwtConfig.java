package com.cns.spring_house.shared.infrastructure.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class JwtConfig {

    @Bean
    public JwtEncoder provideJwtEncoder() {
        SecretKey key = new SecretKeySpec("".getBytes(), SignatureAlgorithm.RS256.getName());
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder provideJwtDecoder() {
        SecretKey key = new SecretKeySpec("".getBytes(), SignatureAlgorithm.RS256.getName());
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

}
