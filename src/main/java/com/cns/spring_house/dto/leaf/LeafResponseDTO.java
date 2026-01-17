package com.cns.spring_house.dto.leaf;

import java.math.BigDecimal;
import java.util.UUID;

import com.cns.spring_house.model.leaf.LeafStatus;

public record LeafResponseDTO(
    UUID id,
    UUID leafPackId,
    LeafStatus status,
    String leafNumber,
    BigDecimal width,
    BigDecimal firstLength,
    BigDecimal secondLength,
    Boolean hasHole
) {}
