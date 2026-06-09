package com.cns.spring_house.customer.dto;

import java.util.UUID;

public record CustomerResponseDTO(

    UUID id,
    String nit,
    String name

) {
    
}
