package com.cns.spring_house.attribute.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.attribute.dto.AttributeResponseDTO;
import com.cns.spring_house.attribute.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.attribute.dto.SearchAttributeRequestDTO;
import com.cns.spring_house.attribute.dto.UpdateAttributeRequestDTO;

public interface AttributeService {
    
    AttributeResponseDTO get(UUID id);
    List<AttributeResponseDTO> getAll(SearchAttributeRequestDTO requestDTO);
    AttributeResponseDTO create(CreateAttributeRequestDTO requestDTO);
    AttributeResponseDTO update(UUID id, UpdateAttributeRequestDTO requestDTO);
    void delete(UUID id);

}
