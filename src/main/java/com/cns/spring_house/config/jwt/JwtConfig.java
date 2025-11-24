package com.cns.spring_house.config.jwt;

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

import com.cns.spring_house.config.properties.SecurityProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
public class JwtConfig {

    private final SecretKey KEY;

    public JwtConfig(SecurityProperties properties) {
        this.KEY = new SecretKeySpec(
                properties.getAccessToken().getSecretKey().getBytes(),
                MacAlgorithm.HS384.name());
    }

    @Bean
    JwtEncoder customJwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(this.KEY));
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
