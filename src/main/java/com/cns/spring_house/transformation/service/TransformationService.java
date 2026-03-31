package com.cns.spring_house.transformation.service;

import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;

public interface TransformationService {
    
    TransformationResponseDTO create(CreateTransformationRequestDTO requestDTO);

}
