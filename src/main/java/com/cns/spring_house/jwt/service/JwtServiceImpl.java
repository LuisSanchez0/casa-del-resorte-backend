package com.cns.spring_house.jwt.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.cns.spring_house.shared.config.security.SecurityProperties;
import com.cns.spring_house.useraccount.entity.UserAccount;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JwtServiceImpl implements JwtService {

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    @Override
    public String generateToken(UserAccount userAccount) {
        Instant now = Instant.now();
        Instant expirationTime = now.plus(
                properties.getAccessToken().getExpirationTime(),
                ChronoUnit.MILLIS);
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS384).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userAccount.getId().toString())
                .issuedAt(now)
                .expiresAt(expirationTime)
                .claim("role", userAccount.getRole().getName())
                .claim("workshop", userAccount.getEmployee().getWorkshop().getId())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

}
