package com.cns.spring_house.sale.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.sale.dto.AddSaleDetailRequestDTO;
import com.cns.spring_house.sale.dto.CreateSaleRequestDTO;
import com.cns.spring_house.sale.dto.SaleResponseDTO;
import com.cns.spring_house.sale.dto.SearchSaleRequestDTO;

public interface SaleService {

    List<SaleResponseDTO> getAll(SearchSaleRequestDTO requestDTO);
    
    SaleResponseDTO get(UUID id);

    SaleResponseDTO create(CreateSaleRequestDTO requestDTO);

    SaleResponseDTO addDetail(UUID id, AddSaleDetailRequestDTO requestDTO);

    SaleResponseDTO removeDetail(UUID saleDetailId);

    SaleResponseDTO completeSale(UUID id);

    void delete(UUID id);

}
