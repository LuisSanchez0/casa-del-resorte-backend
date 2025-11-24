package com.cns.spring_house.service.jwt;

import java.util.UUID;

public interface JwtService {

    String generateToken(UUID userId, String role);

}
