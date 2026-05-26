package com.cns.spring_house.sale.dto;

import java.util.Set;

public record CreateSaleRequestDTO(

    Set<CreateSaleDetailRequestDTO> normalProducts,
    Set<CreateSaleDetailRequestDTO> serializedProduts,
    Set<CreateSaleDetailRequestDTO> jobs

) {
    
}
