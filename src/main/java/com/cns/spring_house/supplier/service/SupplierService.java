package com.cns.spring_house.supplier.service;

import java.util.List;

import com.cns.spring_house.supplier.dto.CreateSupplierRequestDTO;
import com.cns.spring_house.supplier.dto.SupplierResponseDTO;

public interface SupplierService {

    List<SupplierResponseDTO> search(String query, Integer limit);

    SupplierResponseDTO create(CreateSupplierRequestDTO requestDTO);

}
