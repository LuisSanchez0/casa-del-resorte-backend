package com.cns.spring_house.service.workshop;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.workshop.CreateWorkshopRequestDTO;
import com.cns.spring_house.dto.workshop.SearchWorkshopRequestDTO;
import com.cns.spring_house.dto.workshop.UpdateWorkshopRequestDTO;
import com.cns.spring_house.dto.workshop.WorkshopResponseDTO;

public interface WorkshopService {

    List<WorkshopResponseDTO> getAll(SearchWorkshopRequestDTO requestDTO);

    WorkshopResponseDTO get(UUID id);

    WorkshopResponseDTO create(CreateWorkshopRequestDTO requestDTO);

    WorkshopResponseDTO update(UUID id, UpdateWorkshopRequestDTO requestDTO);

}
