package com.cns.spring_house.customer.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.customer.dto.CreateCustomerRequestDTO;
import com.cns.spring_house.customer.dto.CustomerResponseDTO;
import com.cns.spring_house.customer.dto.SearchCustomerRequestDTO;
import com.cns.spring_house.customer.dto.UpdateCustomerRequestDTO;

public interface CustomerService {
    
    List<CustomerResponseDTO> getAll(SearchCustomerRequestDTO requestDTO);
    CustomerResponseDTO get(UUID id);
    CustomerResponseDTO create(CreateCustomerRequestDTO requestDTO);
    CustomerResponseDTO update(UUID id, UpdateCustomerRequestDTO requestDTO);

}
