package com.cns.spring_house.stockitem.dto;

import java.util.UUID;

public record SearchStockItemRequestDTO(

        UUID workshop,
        UUID product

) { }
