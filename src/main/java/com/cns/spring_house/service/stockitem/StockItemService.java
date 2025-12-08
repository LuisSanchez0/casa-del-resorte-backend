package com.cns.spring_house.service.stockitem;

import java.util.List;
import java.util.UUID;

import com.cns.spring_house.dto.stockitem.CreateStockItemRequestDTO;
import com.cns.spring_house.dto.stockitem.SearchStockItemRequestDTO;
import com.cns.spring_house.dto.stockitem.StockItemResponseDTO;
import com.cns.spring_house.dto.stockitem.UpdateStockItemRequestDTO;

public interface StockItemService {
    
    StockItemResponseDTO get(UUID id);

    List<StockItemResponseDTO> getAll(SearchStockItemRequestDTO requestDTO);

    StockItemResponseDTO create(CreateStockItemRequestDTO requestDTO);

    StockItemResponseDTO update(UUID id, UpdateStockItemRequestDTO requestDTO);

}
