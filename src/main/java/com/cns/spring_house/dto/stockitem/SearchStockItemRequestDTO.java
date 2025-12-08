package com.cns.spring_house.dto.stockitem;

import java.util.UUID;

public record SearchStockItemRequestDTO(

        UUID workshop,
        UUID product

) { }
