package com.cns.spring_house.model.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.model.dto.CreateModelRequestDTO;
import com.cns.spring_house.model.dto.ModelResponseDTO;
import com.cns.spring_house.model.dto.SearchModelRequestDTO;
import com.cns.spring_house.model.dto.UpdateModelRequestDTO;

public interface ModelService {
    
    ModelResponseDTO get(UUID id);
    List<ModelResponseDTO> getAll(SearchModelRequestDTO requestDTO);
    ModelResponseDTO create(CreateModelRequestDTO requestDTO);
    ModelResponseDTO update(UUID id, UpdateModelRequestDTO requestDTO);

}
