package com.cns.spring_house.shared.security;

import java.security.SecureRandom;
import java.util.Base64;

public class TokenUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TokenUtils() {

    }

    public static String generateSecureToken(int sizeBytes) {
        byte[] bytes = new byte[sizeBytes];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

}
