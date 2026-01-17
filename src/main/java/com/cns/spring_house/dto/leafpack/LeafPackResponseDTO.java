package com.cns.spring_house.dto.leafpack;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.leaf.LeafResponseDTO;
import com.cns.spring_house.dto.product.ProductResponseDTO;
import com.cns.spring_house.dto.workshop.WorkshopResponseDTO;
import com.cns.spring_house.model.leafpack.LeafPackStatus;

public record LeafPackResponseDTO(
    UUID id,
    Integer originalLeafCount,
    Integer availableLeafCount,
    LeafPackStatus status,
    ProductResponseDTO product,
    WorkshopResponseDTO workshop,
    List<LeafResponseDTO> leaves
) {}
