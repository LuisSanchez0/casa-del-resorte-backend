package com.cns.spring_house.transformation.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.SearchTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;

public interface TransformationService {

    List<TransformationResponseDTO> getAll(SearchTransformationRequestDTO requestDTO);
    TransformationResponseDTO get(UUID id);
    TransformationResponseDTO create(CreateTransformationRequestDTO requestDTO);

}
