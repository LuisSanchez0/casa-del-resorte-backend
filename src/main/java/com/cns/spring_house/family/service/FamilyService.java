package com.cns.spring_house.family.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.family.dto.CreateAttributeRequestDTO;
import com.cns.spring_house.family.dto.CreateFamilyRequestDTO;
import com.cns.spring_house.family.dto.FamilyResponseDTO;
import com.cns.spring_house.family.dto.SearchFamilyRequestDTO;
import com.cns.spring_house.family.dto.UpdateAttributeRequestDTO;
import com.cns.spring_house.family.dto.UpdateFamilyRequestDTO;

public interface FamilyService {
    
    FamilyResponseDTO get(UUID id);
    List<FamilyResponseDTO> getAll(SearchFamilyRequestDTO requestDTO);
    FamilyResponseDTO create(CreateFamilyRequestDTO requestDTO);
    FamilyResponseDTO update(UUID id, UpdateFamilyRequestDTO requestDTO);
    FamilyResponseDTO createAttribute(UUID id, CreateAttributeRequestDTO requestDTO);
    FamilyResponseDTO updateAttribute(UUID id, UUID attrId, UpdateAttributeRequestDTO requestDTO);

}
