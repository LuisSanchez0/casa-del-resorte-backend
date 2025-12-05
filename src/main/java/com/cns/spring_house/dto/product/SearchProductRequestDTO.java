package com.cns.spring_house.dto.product;

import java.util.Map;
import java.util.UUID;


public record SearchProductRequestDTO(
   		String q,
    	UUID family,
    	Map<String, Object> attributes
) {

    public static SearchProductRequestDTO from(Map<String, Object> filters) {
        String q = null;
        UUID family = null;
        if (filters.containsKey("q")) {
            q = String.valueOf(filters.get("q"));
            filters.remove("q");
        }
        if (filters.containsKey("family")) {
            try {
                family = UUID.fromString(String.valueOf(filters.get("family")));
            } catch (IllegalArgumentException e) {
                family = null; 
            }
            filters.remove("family");
        }
        return new SearchProductRequestDTO(q, family, filters);
    }
}
