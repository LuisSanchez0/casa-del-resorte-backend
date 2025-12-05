package com.cns.spring_house.service.family;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.family.CreateFamilyRequestDTO;
import com.cns.spring_house.dto.family.FamilyResponseDTO;
import com.cns.spring_house.dto.family.SearchFamilyRequestDTO;
import com.cns.spring_house.dto.family.UpdateFamilyRequestDTO;

public interface FamilyService {
    
    FamilyResponseDTO get(UUID id);
    List<FamilyResponseDTO> getAll(SearchFamilyRequestDTO requestDTO);
    FamilyResponseDTO create(CreateFamilyRequestDTO requestDTO);
    FamilyResponseDTO update(UUID id, UpdateFamilyRequestDTO requestDTO);

}
