package com.cns.spring_house.dto.product;

import java.util.Map;
import java.util.UUID;

public record SearchProductRequestDTO(
        String q,
        UUID family,
        UUID model,
        Map<String, Object> attributes) {

    public static SearchProductRequestDTO from(Map<String, Object> filters) {
        String q = extractString(filters, "q");
        UUID family = extractUUID(filters, "family");
        UUID model = extractUUID(filters, "model");

        return new SearchProductRequestDTO(q, family, model, filters);
    }

    private static String extractString(Map<String, Object> map, String key) {
        Object value = map.remove(key);
        return value != null ? String.valueOf(value) : null;
    }

    private static UUID extractUUID(Map<String, Object> map, String key) {
        Object value = map.remove(key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
