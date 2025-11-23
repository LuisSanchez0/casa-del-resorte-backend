package com.cns.spring_house.service.jwt;

public interface JwtService {

    String generateToken(String username, String role);

}
