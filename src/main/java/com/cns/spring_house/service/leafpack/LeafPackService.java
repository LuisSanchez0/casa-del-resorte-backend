package com.cns.spring_house.service.leafpack;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.leafpack.CreateLeafPackRequestDTO;
import com.cns.spring_house.dto.leafpack.LeafPackResponseDTO;

public interface LeafPackService {
    LeafPackResponseDTO get(UUID id);
    List<LeafPackResponseDTO> getAll();
    LeafPackResponseDTO create(CreateLeafPackRequestDTO requestDTO);
    LeafPackResponseDTO open(UUID id);
}
