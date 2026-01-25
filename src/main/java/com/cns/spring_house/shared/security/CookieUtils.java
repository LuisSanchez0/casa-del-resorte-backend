package com.cns.spring_house.shared.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

public class CookieUtils {

    private CookieUtils() {

    }

    public static ResponseCookie createCookie(String name, String value, Duration maxAge) {
        return ResponseCookie
                .from(name, value)
                .httpOnly(true)
                // .secure(true)
                .path("/")
                .maxAge(maxAge)
                .sameSite("None")
                .build();
    }

    public static ResponseCookie deleteCookie(String name) {
        return ResponseCookie
                .from(name, "")
                .httpOnly(true)
                // .secure(true)
                .path("/")
                .maxAge(Duration.ZERO)
                .sameSite("None")
                .build();
    }

}
