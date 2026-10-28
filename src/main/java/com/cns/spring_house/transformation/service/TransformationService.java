package com.cns.spring_house.transformation.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.transformation.dto.CreateTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.SearchTransformationRequestDTO;
import com.cns.spring_house.transformation.dto.TransformationResponseDTO;
import com.cns.spring_house.transformation.handler.CurlCountRequirement;

public interface TransformationService {

    List<TransformationResponseDTO> getAll(SearchTransformationRequestDTO requestDTO);
    TransformationResponseDTO get(UUID id);
    TransformationResponseDTO create(CreateTransformationRequestDTO requestDTO);

    /**
     * La misma transformacion, indicando quien decide los colochos de la pieza
     * principal. La comanda fabrica la pieza PEDIDA con
     * {@link CurlCountRequirement#AS_REQUESTED}; la transformacion manual usa
     * {@link #create(CreateTransformationRequestDTO)}, que los exige.
     */
    TransformationResponseDTO create(
            CreateTransformationRequestDTO requestDTO, CurlCountRequirement curlCountRequirement);

}
