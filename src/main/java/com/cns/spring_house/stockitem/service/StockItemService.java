package com.cns.spring_house.stockitem.service;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.stockitem.dto.CreateStockItemRequestDTO;
import com.cns.spring_house.stockitem.dto.SearchStockItemRequestDTO;
import com.cns.spring_house.stockitem.dto.StockItemResponseDTO;
import com.cns.spring_house.stockitem.dto.UpdateStockItemRequestDTO;

public interface StockItemService {
    
    StockItemResponseDTO get(UUID id);

    List<StockItemResponseDTO> getAll(SearchStockItemRequestDTO requestDTO);

    StockItemResponseDTO create(CreateStockItemRequestDTO requestDTO);

    StockItemResponseDTO update(UUID id, UpdateStockItemRequestDTO requestDTO);

}
