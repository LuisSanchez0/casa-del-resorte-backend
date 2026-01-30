package com.cns.spring_house.inventory.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.inventory.dto.CreateInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.SearchInventoryRequestDTO;
import com.cns.spring_house.inventory.dto.InventoryResponseDTO;
import com.cns.spring_house.inventory.dto.UpdateInventoryRequestDTO;

public interface InventoryService {
    
    InventoryResponseDTO get(UUID id);

    List<InventoryResponseDTO> getAll(SearchInventoryRequestDTO requestDTO);

    InventoryResponseDTO create(CreateInventoryRequestDTO requestDTO);

    InventoryResponseDTO update(UUID id, UpdateInventoryRequestDTO requestDTO);

}
