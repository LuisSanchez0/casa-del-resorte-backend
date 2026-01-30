package com.cns.spring_house.inventory.dto;

import java.util.UUID;

public record SearchInventoryRequestDTO(

        UUID workshop,
        UUID product

) { }
