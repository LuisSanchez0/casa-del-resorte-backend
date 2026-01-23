package com.cns.spring_house.config.jwt;

import java.util.UUID;

public interface JwtService {

    String generateToken(UUID userId, String role);

}
