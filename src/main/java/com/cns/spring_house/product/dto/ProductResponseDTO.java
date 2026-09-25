package com.cns.spring_house.product.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.cns.spring_house.shared.barcode.BarcodeIdentity;
import com.cns.spring_house.family.entity.FamilyCategory;

import java.util.Map;
import java.util.UUID;

import com.cns.spring_house.model.dto.ModelResponseDTO;

public record ProductResponseDTO(

        UUID id,
        String sku,
        String name,
        ProductFamilyResponseDTO family,
        ModelResponseDTO model,
        Map<String, Object> attributes

) {
    @JsonProperty("barcodeValue")
    public String barcodeValue() {
        return family != null && family.category() == FamilyCategory.NORMAL
                ? BarcodeIdentity.product(id) : null;
    }
}