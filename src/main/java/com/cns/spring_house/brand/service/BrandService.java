package com.cns.spring_house.brand.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.brand.dto.BrandResponseDTO;
import com.cns.spring_house.brand.dto.CreateBrandRequestDTO;
import com.cns.spring_house.brand.dto.SearchBrandRequestDTO;
import com.cns.spring_house.brand.dto.UpdateBrandRequestDTO;

public interface BrandService {
    
    BrandResponseDTO get(UUID id);
    List<BrandResponseDTO> getAll(SearchBrandRequestDTO requestDTO);
    BrandResponseDTO create(CreateBrandRequestDTO requestDTO);
    BrandResponseDTO update(UUID id, UpdateBrandRequestDTO requestDTO);

}
