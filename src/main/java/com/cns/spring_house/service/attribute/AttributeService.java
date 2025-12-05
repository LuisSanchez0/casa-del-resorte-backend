package com.cns.spring_house.service.attribute;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.attribute.AttributeResponseDTO;
import com.cns.spring_house.dto.attribute.CreateAttributeRequestDTO;
import com.cns.spring_house.dto.attribute.SearchAttributeRequestDTO;
import com.cns.spring_house.dto.attribute.UpdateAttributeRequestDTO;

public interface AttributeService {
    
    AttributeResponseDTO get(UUID id);
    List<AttributeResponseDTO> getAll(SearchAttributeRequestDTO requestDTO);
    AttributeResponseDTO create(CreateAttributeRequestDTO requestDTO);
    AttributeResponseDTO update(UUID id, UpdateAttributeRequestDTO requestDTO);
    void delete(UUID id);

}
