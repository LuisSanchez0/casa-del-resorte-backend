package com.cns.spring_house.inventory.dto;

import java.util.List;
import java.util.UUID;

public record AddStockRequestDTO(

        Integer units,
        List<UUID> children

) {
    
}
