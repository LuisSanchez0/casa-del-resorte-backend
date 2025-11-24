package com.cns.spring_house.service.jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.cns.spring_house.config.properties.SecurityProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    @Override
    public String generateToken(UUID userId, String role) {
        Instant now = Instant.now();
        Instant expirationTime = now.plus(
                properties.getAccessToken().getExpirationTime(),
                ChronoUnit.MILLIS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expirationTime)
                .claim("role", role)
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

}
