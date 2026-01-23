package com.cns.spring_house.workshop.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.workshop.dto.CreateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.SearchWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.UpdateWorkshopRequestDTO;
import com.cns.spring_house.workshop.dto.WorkshopResponseDTO;

public interface WorkshopService {

    List<WorkshopResponseDTO> getAll(SearchWorkshopRequestDTO requestDTO);

    WorkshopResponseDTO get(UUID id);

    WorkshopResponseDTO create(CreateWorkshopRequestDTO requestDTO);

    WorkshopResponseDTO update(UUID id, UpdateWorkshopRequestDTO requestDTO);

}
