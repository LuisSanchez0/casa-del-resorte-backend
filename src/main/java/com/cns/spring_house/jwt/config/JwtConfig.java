package com.cns.spring_house.jwt.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import com.cns.spring_house.shared.config.security.SecurityProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
public class JwtConfig {

    private final SecretKey KEY;

    public JwtConfig(SecurityProperties properties) {
        this.KEY = new SecretKeySpec(
                properties.getAccessToken().getSecretKey().getBytes(),
                MacAlgorithm.HS384.getName());
    }

    @Bean
    JwtEncoder customJwtEncoder() {
        JWKSource<SecurityContext> source = new ImmutableSecret<>(this.KEY);
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtDecoder customJwtDecoder() {
        return NimbusJwtDecoder
                .withSecretKey(this.KEY)
                .macAlgorithm(MacAlgorithm.HS384)
                .build();
    }

    @Bean
    JwtAuthenticationConverter customJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("role");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return authenticationConverter;
    }

}
