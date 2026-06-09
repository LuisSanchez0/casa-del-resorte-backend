package com.cns.spring_house.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateCustomerRequestDTO(

    @NotBlank(message = "El nit es obligatorio")
    @Pattern(regexp = "^\\d{8,13}$", message = "El nit debe contener de 8 a 13 digitos")
    String nit,
    @NotBlank(message = "El nombre es obligatorio")
    String name

) {

}
