package com.cns.spring_house.customer.dto;

import jakarta.validation.constraints.Pattern;

public record SearchCustomerRequestDTO(

    @Pattern(regexp = "^\\d{8,13}$", message = "El nit debe contener de 8 a 13 digitos")
    String nit,
    String name

) {
    
}
