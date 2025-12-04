package com.cns.spring_house.service.model;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.model.CreateModelRequestDTO;
import com.cns.spring_house.dto.model.ModelResponseDTO;
import com.cns.spring_house.dto.model.SearchModelRequestDTO;
import com.cns.spring_house.dto.model.UpdateModelRequestDTO;

public interface ModelService {
    
    ModelResponseDTO get(UUID id);
    List<ModelResponseDTO> getAll(SearchModelRequestDTO requestDTO);
    ModelResponseDTO create(CreateModelRequestDTO requestDTO);
    ModelResponseDTO update(UUID id, UpdateModelRequestDTO requestDTO);

}
