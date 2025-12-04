package com.cns.spring_house.service.brand;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.brand.BrandResponseDTO;
import com.cns.spring_house.dto.brand.CreateBrandRequestDTO;
import com.cns.spring_house.dto.brand.SearchBrandRequestDTO;
import com.cns.spring_house.dto.brand.UpdateBrandRequestDTO;

public interface BrandService {
    
    BrandResponseDTO get(UUID id);
    List<BrandResponseDTO> getAll(SearchBrandRequestDTO requestDTO);
    BrandResponseDTO create(CreateBrandRequestDTO requestDTO);
    BrandResponseDTO update(UUID id, UpdateBrandRequestDTO requestDTO);

}
