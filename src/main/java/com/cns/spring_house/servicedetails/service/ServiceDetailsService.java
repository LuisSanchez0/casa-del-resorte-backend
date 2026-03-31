package com.cns.spring_house.servicedetails.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.servicedetails.dto.CreateServiceDetailsRequestDTO;
import com.cns.spring_house.servicedetails.dto.ServiceDetailsResponseDTO;
import com.cns.spring_house.servicedetails.dto.UpdateServiceDetailsRequestDTO;

public interface ServiceDetailsService {
    
    List<ServiceDetailsResponseDTO> getAll();
    ServiceDetailsResponseDTO get(UUID id);
    ServiceDetailsResponseDTO create(CreateServiceDetailsRequestDTO requestDTO);
    ServiceDetailsResponseDTO update(UUID id, UpdateServiceDetailsRequestDTO requestDTO);

}
